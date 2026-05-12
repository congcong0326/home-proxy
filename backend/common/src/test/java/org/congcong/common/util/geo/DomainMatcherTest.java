package org.congcong.common.util.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainMatcherTest {

    @Test
    void wildcardMatchesAnyNonBlankHost() {
        assertTrue(DomainMatcher.matches("my-home-page.com", "*"));
        assertFalse(DomainMatcher.matches("", "*"));
    }

    @Test
    void dotPrefixMatchesBaseDomainAndSubdomains() {
        assertTrue(DomainMatcher.matches("my-home-page.com", ".my-home-page.com"));
        assertTrue(DomainMatcher.matches("www.my-home-page.com", ".my-home-page.com"));
        assertFalse(DomainMatcher.matches("other-home-page.com", ".my-home-page.com"));
    }

    @Test
    void starDotPrefixMatchesSubdomainsOnly() {
        assertFalse(DomainMatcher.matches("my-home-page.com", "*.my-home-page.com"));
        assertTrue(DomainMatcher.matches("www.my-home-page.com", "*.my-home-page.com"));
    }

    @Test
    void bareDomainMatchesExactly() {
        assertTrue(DomainMatcher.matches("my-home-page.com", "my-home-page.com"));
        assertFalse(DomainMatcher.matches("www.my-home-page.com", "my-home-page.com"));
    }
}
