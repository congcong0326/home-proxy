package org.congcong.common.util.geo;

import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.DomainRuleType;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public final class DomainRuleEngine {

    private static final ScheduledExecutorService EXECUTOR =
            Executors.newScheduledThreadPool(1);

    private static final Map<DomainRuleType, DomainRuleLoader> LOADERS =
            new EnumMap<>(DomainRuleType.class);

    private static final ConcurrentMap<DomainRuleType, DomainRuleSet> RULES =
            new ConcurrentHashMap<>();

    private static volatile boolean init;

    static {
        LOADERS.put(DomainRuleType.GEO_FOREIGN, new GeolocationNotCnLoader());
        LOADERS.put(DomainRuleType.GEO_CN, new GeolocationCnLoader());
        LOADERS.put(DomainRuleType.AD, new AdBlockRuleLoader());
        LOADERS.put(DomainRuleType.DOMAIN, new DomainFakeLoader());
    }

    private DomainRuleEngine() {
    }

    public static void init() {
        EXECUTOR.scheduleAtFixedRate(DomainRuleEngine::refreshAll, 0, 1, TimeUnit.DAYS);
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    public static MatchResult match(DomainRuleType type, String host) {
        return match(type, host, null);
    }

    public static MatchResult match(DomainRuleType type, String host, String target) {
        if (!init || host == null || host.isEmpty()) {
            return new MatchResult(false, type, DomainTrie.MatchType.NONE);
        }
        DomainRuleSet set = RULES.get(type);
        if (set == null) {
            return new MatchResult(false, type, DomainTrie.MatchType.NONE);
        }
        DomainTrie.MatchType mt = set.matchType(host, target);
        return new MatchResult(mt != DomainTrie.MatchType.NONE, type, mt);
    }

    private static void refreshAll() {
        init = false;
        boolean success = true;
        for (Map.Entry<DomainRuleType, DomainRuleLoader> e : LOADERS.entrySet()) {
            try {
                RULES.put(e.getKey(), e.getValue().load());
            } catch (Exception ex) {
                success = false;
                log.warn("refresh {} rule set failed: {}", e.getKey(), ex.getMessage(), ex);
            }
        }
        init = success;
        if (success) {
            log.info("domain rule engine refreshed");
        }
    }
}