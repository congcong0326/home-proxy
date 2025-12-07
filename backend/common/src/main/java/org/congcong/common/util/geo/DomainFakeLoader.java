package org.congcong.common.util.geo;

public class DomainFakeLoader implements DomainRuleLoader {

    @Override
    public DomainRuleSet load() throws Exception {
        return new DomainRuleSet(){

            public DomainTrie.MatchType matchType(String host, String target) {
                boolean b = matchDomain(host, target);
                return b ? DomainTrie.MatchType.DOMAIN : DomainTrie.MatchType.NONE;
            }

            /**
             * 支持域名匹配，如：example.com
             * 支持通配符匹配，如：*.example.com
             * 支持子域名匹配，如：sub.example.com
             * @param host
             * @param value
             * @return
             */
            private boolean matchDomain(String host, String value) {
                if (host == null || value == null) {
                    return false;
                }
                String h = normalizeHost(host);
                String v = normalizeHost(value);

                // 全匹配：任意非空 host
                if ("*".equals(v)) {
                    return !h.isEmpty();
                }

                // 后缀匹配（含主域）：".example.com" -> 匹配 example.com 与 *.example.com
                if (v.startsWith(".")) {
                    String base = v.substring(1);       // "example.com"
                    String suffix = v;                  // ".example.com"
                    return h.equals(base) || (h.endsWith(suffix) && h.length() > suffix.length());
                }

                // 仅子域通配： "*.example.com" 不匹配主域
                if (v.startsWith("*.")) {
                    String suffix = v.substring(1);     // ".example.com"
                    return h.endsWith(suffix) && h.length() > suffix.length();
                }

                // 精确匹配
                return h.equals(v);
            }

            private String normalizeHost(String input) {
                String s = input.trim();
                // 去掉 FQDN 末尾点
                if (s.endsWith(".")) {
                    s = s.substring(0, s.length() - 1);
                }
                // 统一小写并处理 IDN
                try {
                    s = java.net.IDN.toASCII(s.toLowerCase());
                } catch (IllegalArgumentException e) {
                    s = s.toLowerCase();
                }
                return s;
            }
        };
    }
}
