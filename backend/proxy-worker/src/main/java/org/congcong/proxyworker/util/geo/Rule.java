package org.congcong.proxyworker.util.geo;

import java.util.Set;

public class Rule {
    private final RuleType type;
    private final String value;
    private final Set<String> attrs; // @cn, @!cn, @ads 等，暂时可以不用

    public Rule(RuleType type, String value, Set<String> attrs) {
        this.type = type;
        this.value = value;
        this.attrs = attrs;
    }

    public RuleType getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public Set<String> getAttrs() {
        return attrs;
    }
}
