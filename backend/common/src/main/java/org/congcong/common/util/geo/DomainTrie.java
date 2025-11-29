package org.congcong.common.util.geo;

import java.util.HashMap;
import java.util.Map;

public class DomainTrie {

    public static class Node {
        Map<String, Node> children = new HashMap<>();
        boolean hasDomainRule; // domain: 或无前缀规则
        boolean hasFullRule;   // full: 规则
    }

    private final Node root = new Node();

    public void addDomain(String domain) {
        add(domain, false);
    }

    public void addFull(String fullDomain) {
        add(fullDomain, true);
    }

    private void add(String host, boolean isFull) {
        String normalized = host.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return;
        }
        String[] labels = normalized.split("\\.");
        Node cur = root;
        // 反向插入：从 TLD 开始
        for (int i = labels.length - 1; i >= 0; i--) {
            String label = labels[i];
            if (label.isEmpty()) continue;
            cur = cur.children.computeIfAbsent(label, k -> new Node());
        }
        if (isFull) {
            cur.hasFullRule = true;
        } else {
            cur.hasDomainRule = true;
        }
    }

    /**
     * 返回是否命中规则（full 优先，其次 domain）。
     * 你也可以改成返回一个枚举：NONE/DOMAIN/FULL，看你需求。
     */
    public boolean matches(String host) {
        return matchType(host) != MatchType.NONE;
    }

    public enum MatchType {
        NONE, DOMAIN, FULL
    }

    public MatchType matchType(String host) {
        if (host == null || host.isEmpty()) {
            return MatchType.NONE;
        }
        String normalized = host.toLowerCase();
        // 去掉结尾的点，比如 "example.com."
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String[] labels = normalized.split("\\.");
        Node cur = root;
        MatchType best = MatchType.NONE;

        // 从 TLD 往前走
        for (int i = labels.length - 1; i >= 0; i--) {
            String label = labels[i];
            cur = cur.children.get(label);
            if (cur == null) {
                break;
            }
            // domain: 规则：只要走到这个节点就算匹配（包括子域名）
            if (cur.hasDomainRule) {
                best = MatchType.DOMAIN;
            }
            // full: 规则：必须刚好用完所有 label 才算
            if (cur.hasFullRule && i == 0) {
                best = MatchType.FULL;
                // FULL 可以直接认为是最优命中，提前结束
                break;
            }
        }
        return best;
    }
}
