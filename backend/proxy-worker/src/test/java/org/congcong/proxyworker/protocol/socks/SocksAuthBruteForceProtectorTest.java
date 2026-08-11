package org.congcong.proxyworker.protocol.socks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SocksAuthBruteForceProtectorTest {
    private final AtomicLong now = new AtomicLong();
    private SocksAuthBruteForceProtector protector;

    @BeforeEach
    void setUp() {
        protector = new SocksAuthBruteForceProtector(now::get);
    }

    @Test
    void locksUsernameAfterFiveFailuresInsideWindow() {
        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES - 1; i++) {
            protector.recordFailure("alice");
        }

        assertFalse(protector.isLocked("alice"));
        protector.recordFailure("alice");
        assertTrue(protector.isLocked("alice"));
    }

    @Test
    void excludesFailuresOutsideFiveMinuteWindow() {
        protector.recordFailure("alice");
        now.addAndGet(SocksAuthBruteForceProtector.FAILURE_WINDOW_MILLIS + 1);

        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES - 1; i++) {
            protector.recordFailure("alice");
        }

        assertFalse(protector.isLocked("alice"));
        protector.recordFailure("alice");
        assertTrue(protector.isLocked("alice"));
    }

    @Test
    void doesNotExtendLockWhenMoreFailuresAreRecorded() {
        lock("alice");
        now.addAndGet(TimeUnit.MINUTES.toMillis(10));

        protector.recordFailure("alice");
        now.addAndGet(TimeUnit.MINUTES.toMillis(6));

        assertFalse(protector.isLocked("alice"));
    }

    @Test
    void clearsFailuresAfterSuccessfulAuthentication() {
        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES - 1; i++) {
            protector.recordFailure("alice");
        }

        protector.clearFailures("alice");
        protector.recordFailure("alice");

        assertFalse(protector.isLocked("alice"));
    }

    @Test
    void recordsConcurrentFailuresWithoutMissingTheLockThreshold() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(SocksAuthBruteForceProtector.MAX_FAILURES);
        CountDownLatch ready = new CountDownLatch(SocksAuthBruteForceProtector.MAX_FAILURES);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        protector.recordFailure("alice");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            assertTrue(protector.isLocked("alice"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void keepsAtMostTenThousandUsernameStates() {
        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_ENTRIES + 100; i++) {
            protector.recordFailure("user-" + i);
        }

        assertTrue(protector.cachedUsernameCount() <= SocksAuthBruteForceProtector.MAX_ENTRIES);
    }

    private void lock(String username) {
        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES; i++) {
            protector.recordFailure(username);
        }
        assertTrue(protector.isLocked(username));
    }
}
