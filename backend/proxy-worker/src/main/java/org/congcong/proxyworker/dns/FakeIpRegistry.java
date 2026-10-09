package org.congcong.proxyworker.dns;

import com.google.common.net.InetAddresses;

import java.net.IDN;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/** Process-wide IPv4 lease table. DNS TTL does not expire a lease. */
public final class FakeIpRegistry {
    private static final int FIRST_ADDRESS = 0xC6120001; // 198.18.0.1
    private static final int LAST_ADDRESS = 0xC613FFFE;  // 198.19.255.254
    private static final int POOL_START = 0xC6120000;
    private static final int POOL_END = 0xC613FFFF;
    public static final int DEFAULT_CAPACITY = 32_768;
    private static final FakeIpRegistry INSTANCE = new FakeIpRegistry(DEFAULT_CAPACITY);

    private final int capacity;
    private final LinkedHashMap<String, Entry> byDomain = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Integer, Entry> byIp = new HashMap<>();
    private final ArrayDeque<Integer> reusable = new ArrayDeque<>();
    private final LongAdder allocations = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder reverseMisses = new LongAdder();
    private int nextAddress = FIRST_ADDRESS;

    public static FakeIpRegistry getInstance() {
        return INSTANCE;
    }

    public FakeIpRegistry(int capacity) {
        if (capacity < 1 || capacity > (long) LAST_ADDRESS - FIRST_ADDRESS + 1) {
            throw new IllegalArgumentException("Fake IP LRU 容量超出地址池范围");
        }
        this.capacity = capacity;
    }

    public static String normalizeDomain(String domain) {
        if (domain == null) throw new IllegalArgumentException("域名不能为空");
        String normalized = domain.trim();
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) throw new IllegalArgumentException("域名不能为空");
        return IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized String getOrAllocate(String domain) {
        String key = normalizeDomain(domain);
        Entry existing = byDomain.get(key);
        if (existing != null) return toIp(existing.address());

        if (byDomain.size() == capacity) {
            Map.Entry<String, Entry> eldest = byDomain.entrySet().iterator().next();
            byDomain.remove(eldest.getKey());
            byIp.remove(eldest.getValue().address());
            reusable.addLast(eldest.getValue().address());
            evictions.increment();
        }
        int address = nextAddress <= LAST_ADDRESS ? nextAddress++ : reusable.removeFirst();
        Entry entry = new Entry(key, address);
        byDomain.put(key, entry);
        byIp.put(address, entry);
        allocations.increment();
        return toIp(address);
    }

    public synchronized Optional<String> reverseLookup(String ip) {
        Integer address = parseAddress(ip);
        if (address == null || !isInPool(address)) return Optional.empty();
        Entry entry = byIp.get(address);
        if (entry == null) {
            reverseMisses.increment();
            return Optional.empty();
        }
        byDomain.get(entry.domain()); // reverse lookup counts as LRU use
        return Optional.of(entry.domain());
    }

    public static boolean isInPool(String ip) {
        Integer address = parseAddress(ip);
        return address != null && isInPool(address);
    }

    public long allocations() { return allocations.sum(); }
    public long evictions() { return evictions.sum(); }
    public long reverseMisses() { return reverseMisses.sum(); }

    private static boolean isInPool(int address) {
        return Integer.compareUnsigned(address, POOL_START) >= 0
                && Integer.compareUnsigned(address, POOL_END) <= 0;
    }

    private static Integer parseAddress(String ip) {
        if (ip == null || !InetAddresses.isInetAddress(ip)) return null;
        InetAddress address = InetAddresses.forString(ip);
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return null;
        return ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
    }

    private static String toIp(int value) {
        return ((value >>> 24) & 0xff) + "." + ((value >>> 16) & 0xff) + "."
                + ((value >>> 8) & 0xff) + "." + (value & 0xff);
    }

    private record Entry(String domain, int address) {}
}
