package org.congcong.proxyworker.protocol.socks;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Keeps bounded, per-username SOCKS5 password authentication failure state.
 */
public final class SocksAuthBruteForceProtector {
    static final int MAX_FAILURES = 5;
    static final int MAX_ENTRIES = 10_000;
    static final long FAILURE_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5);
    static final long LOCK_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final long STATE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(20);

    private final Cache<String, AuthFailureState> failureStates;
    private final LongSupplier currentTimeMillis;

    public SocksAuthBruteForceProtector() {
        this(System::currentTimeMillis);
    }

    SocksAuthBruteForceProtector(LongSupplier currentTimeMillis) {
        this.failureStates = CacheBuilder.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(STATE_TTL_MILLIS, TimeUnit.MILLISECONDS)
                .build();
        this.currentTimeMillis = currentTimeMillis;
    }

    public boolean isLocked(String username) {
        AuthFailureState state = failureStates.getIfPresent(username);
        return state != null && state.isLocked(currentTimeMillis.getAsLong());
    }

    public void recordFailure(String username) {
        long now = currentTimeMillis.getAsLong();
        ConcurrentMap<String, AuthFailureState> states = failureStates.asMap();

        while (true) {
            AuthFailureState current = states.get(username);
            if (current == null) {
                AuthFailureState updated = AuthFailureState.empty().recordFailure(now);
                if (states.putIfAbsent(username, updated) == null) {
                    return;
                }
                continue;
            }
            if (current.isLocked(now)) {
                return;
            }

            AuthFailureState updated = current.recordFailure(now);
            if (states.replace(username, current, updated)) {
                return;
            }
        }
    }

    public void clearFailures(String username) {
        failureStates.invalidate(username);
    }

    long cachedUsernameCount() {
        failureStates.cleanUp();
        return failureStates.size();
    }

    private record AuthFailureState(long[] failureTimestamps, long lockedUntil) {
        private static final long[] NO_FAILURES = new long[0];

        static AuthFailureState empty() {
            return new AuthFailureState(NO_FAILURES, 0);
        }

        boolean isLocked(long now) {
            return lockedUntil > now;
        }

        AuthFailureState recordFailure(long now) {
            if (isLocked(now)) {
                return this;
            }

            long windowStart = now - FAILURE_WINDOW_MILLIS;
            long[] activeFailures = new long[MAX_FAILURES];
            int activeCount = 0;
            for (long failureTimestamp : failureTimestamps) {
                if (failureTimestamp >= windowStart) {
                    activeFailures[activeCount++] = failureTimestamp;
                }
            }

            if (activeCount == MAX_FAILURES - 1) {
                return new AuthFailureState(NO_FAILURES, now + LOCK_DURATION_MILLIS);
            }

            activeFailures[activeCount] = now;
            long[] updatedFailures = new long[activeCount + 1];
            System.arraycopy(activeFailures, 0, updatedFailures, 0, updatedFailures.length);
            return new AuthFailureState(updatedFailures, 0);
        }
    }
}
