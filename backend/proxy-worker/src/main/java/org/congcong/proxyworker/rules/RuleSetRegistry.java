package org.congcong.proxyworker.rules;

import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.RuleSetDTO;
import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.enums.RuleSetItemType;
import org.congcong.common.enums.RuleSetMatchTarget;
import org.congcong.common.util.geo.DomainTrie;

import java.net.IDN;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public final class RuleSetRegistry {

    private static final Map<String, CompiledRuleSet> RULE_SETS = new ConcurrentHashMap<>();

    private RuleSetRegistry() {
    }

    public static void refresh(List<RuleSetDTO> ruleSets) {
        Map<String, CompiledRuleSet> compiled = new HashMap<>();
        if (ruleSets != null) {
            for (RuleSetDTO ruleSet : ruleSets) {
                if (ruleSet == null || ruleSet.getRuleKey() == null || Boolean.FALSE.equals(ruleSet.getEnabled())) {
                    continue;
                }
                compiled.put(ruleSet.getRuleKey(), compile(ruleSet));
            }
        }
        RULE_SETS.clear();
        RULE_SETS.putAll(compiled);
        log.info("已刷新规则集注册表，数量: {}", RULE_SETS.size());
    }

    public static boolean match(String ruleSetKey, String host) {
        if (ruleSetKey == null || ruleSetKey.isBlank() || host == null || host.isBlank()) {
            return false;
        }
        CompiledRuleSet ruleSet = RULE_SETS.get(ruleSetKey);
        if (ruleSet == null) {
            return false;
        }
        return ruleSet.matches(host);
    }

    private static CompiledRuleSet compile(RuleSetDTO dto) {
        DomainTrie trie = new DomainTrie();
        List<String> keywords = new ArrayList<>();
        List<RuleSetItemDTO> items = dto.getItems();
        if (items != null) {
            for (RuleSetItemDTO item : items) {
                if (item == null || item.getType() == null || item.getValue() == null || item.getValue().isBlank()) {
                    continue;
                }
                String normalized = normalizeHost(item.getValue());
                if (normalized.isEmpty()) {
                    continue;
                }
                if (item.getType() == RuleSetItemType.DOMAIN) {
                    trie.addFull(normalized);
                } else if (item.getType() == RuleSetItemType.DOMAIN_SUFFIX) {
                    trie.addDomain(normalized);
                } else if (item.getType() == RuleSetItemType.DOMAIN_KEYWORD) {
                    keywords.add(normalized);
                }
            }
        }
        return new CompiledRuleSet(dto.getMatchTarget(), trie, keywords);
    }

    private static String normalizeHost(String input) {
        String normalized = input.trim();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            normalized = IDN.toASCII(normalized.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    private record CompiledRuleSet(RuleSetMatchTarget matchTarget, DomainTrie trie, List<String> keywords) {

        private boolean matches(String host) {
            if (matchTarget != RuleSetMatchTarget.DOMAIN) {
                return false;
            }
            String normalizedHost = normalizeHost(host);
            if (trie.matchType(normalizedHost) != DomainTrie.MatchType.NONE) {
                return true;
            }
            for (String keyword : keywords) {
                if (normalizedHost.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }
}
