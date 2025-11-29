package org.congcong.proxyworker.util.geo;

public class DomainClassifier {



    private static DomainRuleSet foreignRuleSet;

    public static void init(DomainRuleSet foreignRuleSet) {
        DomainClassifier.foreignRuleSet = foreignRuleSet;
    }

    /**
     * 简单判断：命中 geolocation-!cn 规则就认为是“国外域名”。
     */
    public static boolean isForeign(String host) {
        return foreignRuleSet.isMatched(host);
    }
}
