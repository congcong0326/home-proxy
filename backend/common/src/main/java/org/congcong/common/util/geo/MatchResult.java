package org.congcong.common.util.geo;

import lombok.Value;
import org.congcong.common.enums.DomainRuleType;

@Value
public class MatchResult {
    boolean matched;
    DomainRuleType type;
    DomainTrie.MatchType matchType;
}