package com.coloryr.allmusic.server.core.api.qqmusic;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicRequestTrackerTest {
    @Test
    void executesOverlappingRequestsInsteadOfDroppingTheSecond() throws Exception {
        QQMusicRequestTracker tracker = new QQMusicRequestTracker();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(() -> tracker.execute(() -> {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                return "first";
            }));
            Future<String> second = executor.submit(() -> tracker.execute(() -> {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                return "second";
            }));

            assertTrue(entered.await(2, TimeUnit.SECONDS),
                    "both overlapping requests should enter their operations");
            assertTrue(tracker.isBusy());

            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
            assertFalse(tracker.isBusy());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesBusyStateWhenARequestFails() {
        QQMusicRequestTracker tracker = new QQMusicRequestTracker();

        assertThrows(IllegalStateException.class, () -> tracker.execute(() -> {
            throw new IllegalStateException("test failure");
        }));
        assertFalse(tracker.isBusy());
    }
}
