package org.congcong.common.util.geo;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DomainClassifier {


    private static final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    private static volatile boolean init = false;

    private static volatile DomainRuleSet foreignRuleSet;

    private static volatile DomainRuleSet cnRuleSet;

    public static void init() {
        executor.scheduleAtFixedRate(DomainClassifier::refresh, 0, 1, TimeUnit.DAYS);
    }

    private static void refresh() {
        init = false;
        log.info("begin refresh rule set");
        try {
            foreignRuleSet = new GeolocationNotCnLoader().load();
            cnRuleSet = new GeolocationCnLoader().load();
            init = true;
            log.info("refresh rule set successfully");
        } catch (Exception e) {
            init = false;
            log.warn("refresh rule set failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 简单判断：命中 geolocation-!cn 规则就认为是“国外域名”。
     */
    public static ForeignResult isForeign(String host) {
        ForeignResult foreignResult = new ForeignResult();
        if (!init || host == null || host.isEmpty()) {
            foreignResult.setForeign(false);
            foreignResult.setSure(true);
            return foreignResult;
        }
        boolean isForeign = foreignRuleSet.isMatched(host);
        if (isForeign) {
            foreignResult.setSure(true);
            foreignResult.setForeign(true);
        } else {
            boolean isCn = cnRuleSet.isMatched(host);
            if (isCn) {
                foreignResult.setSure(true);
                foreignResult.setForeign(false);
            } else {
                foreignResult.setSure(false);
            }
        }
        return foreignResult;
    }

    public static void shutdown() {
        executor.shutdownNow();
    }
}
