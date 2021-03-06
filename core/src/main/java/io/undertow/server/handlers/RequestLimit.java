package io.undertow.server.handlers;

import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static org.xnio.Bits.longBitMask;

/**
 * Represents a limit on a number of running requests.
 *
 * This is basically a counter with a configured set of limits, that is used by {@link RequestLimitingHandler}.
 *
 * When the number of active requests goes over the configured max requests then requests will be suspended and queued.
 *
 * If the queue is full requests will be rejected with a 513.
 *
 * The reason why this is abstracted out into a separate class is so that multiple handlers can share the same state. This
 * allows for fine grained control of resources.
 *
 * @see RequestLimitingHandler
 * @author Stuart Douglas
 */
public class RequestLimit {
    @SuppressWarnings("unused")
    private volatile long state;

    private static final AtomicLongFieldUpdater<RequestLimit> stateUpdater = AtomicLongFieldUpdater.newUpdater(RequestLimit.class, "state");

    private static final long MASK_MAX = longBitMask(32, 63);
    private static final long MASK_CURRENT = longBitMask(0, 30);

    /**
     * The handler that will be invoked if the queue is full.
     */
    private volatile HttpHandler failureHandler = new ResponseCodeHandler(513);

    private final Queue<SuspendedRequest> queue;

    private final ExchangeCompletionListener COMPLETION_LISTENER = new ExchangeCompletionListener() {

        @Override
        public void exchangeEvent(final HttpServerExchange exchange, final NextListener nextListener) {
            try {
                final SuspendedRequest task = queue.poll();
                if (task != null) {
                    task.exchange.dispatch(task.next);
                } else {
                    decrementRequests();
                }
            } finally {
                nextListener.proceed();
            }
        }
    };


    public RequestLimit(int maximumConcurrentRequests) {
        this(maximumConcurrentRequests, -1);
    }

    /**
     * Construct a new instance. The maximum number of concurrent requests must be at least one.
     *
     * @param maximumConcurrentRequests the maximum concurrent requests
     * @param queueSize                 The maximum number of requests to queue
     */
    public RequestLimit(int maximumConcurrentRequests, int queueSize) {
        if (maximumConcurrentRequests < 1) {
            throw new IllegalArgumentException("Maximum concurrent requests must be at least 1");
        }
        state = (maximumConcurrentRequests & 0xFFFFFFFFL) << 32;

        this.queue = new LinkedBlockingQueue<SuspendedRequest>(queueSize <= 0 ? Integer.MAX_VALUE : queueSize);
    }

    public void handleRequest(final HttpServerExchange exchange, HttpHandler next) throws Exception {
        exchange.addExchangeCompleteListener(COMPLETION_LISTENER);
        long oldVal, newVal;
        do {
            oldVal = state;
            final long current = oldVal & MASK_CURRENT;
            final long max = (oldVal & MASK_MAX) >> 32L;
            if (current >= max) {
                if(!queue.offer(new SuspendedRequest(exchange, next))) {
                    failureHandler.handleRequest(exchange);
                }
                return;
            }
            newVal = oldVal + 1;
        } while (!stateUpdater.compareAndSet(this, oldVal, newVal));
        next.handleRequest(exchange);
    }

    /**
     * Get the maximum concurrent requests.
     *
     * @return the maximum concurrent requests
     */
    public int getMaximumConcurrentRequests() {
        return (int) (state >> 32L);
    }

    /**
     * Set the maximum concurrent requests.  The value must be greater than or equal to one.
     *
     * @param newMax the maximum concurrent requests
     */
    public int setMaximumConcurrentRequests(int newMax) {
        if (newMax < 1) {
            throw new IllegalArgumentException("Maximum concurrent requests must be at least 1");
        }
        long oldVal, newVal;
        int current, oldMax;
        do {
            oldVal = state;
            current = (int) (oldVal & MASK_CURRENT);
            oldMax = (int) ((oldVal & MASK_MAX) >> 32L);
            newVal = current | newMax & 0xFFFFFFFFL << 32L;
        } while (!stateUpdater.compareAndSet(this, oldVal, newVal));
        while (current < newMax) {
            // more space opened up!  Process queue entries for a while
            final SuspendedRequest request = queue.poll();
            if (request != null) {
                // now bump up the counter by one; this *could* put us over the max if it changed in the meantime but that's OK
                newVal = stateUpdater.getAndIncrement(this);
                current = (int) (newVal & MASK_CURRENT);
                request.exchange.dispatch(request.next);
            }
        }
        return oldMax;
    }

    private void decrementRequests() {
        stateUpdater.decrementAndGet(this);
    }

    public HttpHandler getFailureHandler() {
        return failureHandler;
    }

    public void setFailureHandler(HttpHandler failureHandler) {
        this.failureHandler = failureHandler;
    }

    private static final class SuspendedRequest {
        final HttpServerExchange exchange;
        final HttpHandler next;

        private SuspendedRequest(HttpServerExchange exchange, HttpHandler next) {
            this.exchange = exchange;
            this.next = next;
        }
    }
}
