package org.congcong.proxyworker.security;

import java.util.concurrent.atomic.LongAdder;

/** Process-wide counts of rejected outbound destination addresses. */
public final class ProxySafetyCounters {
    private static final LongAdder POOL_ADDRESSES = new LongAdder();
    private static final LongAdder DESTINATION_ADDRESSES = new LongAdder();

    private ProxySafetyCounters() {}

    public static void rejectedPoolAddress() { POOL_ADDRESSES.increment(); }
    public static void rejectedDestinationAddress() { DESTINATION_ADDRESSES.increment(); }
    public static long rejectedPoolAddresses() { return POOL_ADDRESSES.sum(); }
    public static long rejectedDestinationAddresses() { return DESTINATION_ADDRESSES.sum(); }
}
