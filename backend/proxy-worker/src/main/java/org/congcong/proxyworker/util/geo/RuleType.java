package org.congcong.proxyworker.util.geo;

public enum RuleType {
    DOMAIN,     // domain:example.com 或无前缀的 example.com
    FULL,       // full:alt1-mtalk.google.com
    KEYWORD,    // keyword:google
    REGEXP      // regexp:^.*\.google\.com$
}
