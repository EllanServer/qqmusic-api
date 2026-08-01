package com.coloryr.allmusic.server.core.api.qqmusic;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/** Tracks active requests without rejecting overlapping AllMusic operations. */
final class QQMusicRequestTracker {
    private final AtomicInteger activeRequests = new AtomicInteger();

    <T> T execute(Callable<T> request) throws Exception {
        activeRequests.incrementAndGet();
        try {
            return request.call();
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    boolean isBusy() {
        return activeRequests.get() > 0;
    }
}
