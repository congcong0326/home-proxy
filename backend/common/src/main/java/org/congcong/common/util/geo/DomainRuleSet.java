package org.congcong.common.util.geo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class DomainRuleSet {

    private final DomainTrie trie = new DomainTrie();

    public static DomainRuleSet loadFromStream(InputStream in) throws IOException {
        DomainRuleSet set = new DomainRuleSet();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                set.parseAndAddLine(line);
            }
        }
        return set;
    }

    public boolean isMatched(String host) {
        return trie.matches(host);
    }

    public DomainTrie.MatchType matchType(String host) {
        return trie.matchType(host);
    }

    private void parseAndAddLine(String rawLine) {
        String line = stripComment(rawLine).trim();
        if (line.isEmpty()) {
            return;
        }

        // geosite 里可能有 include:xxx，这里直接跳过或自己实现递归加载
        if (line.startsWith("include:")) {
            // TODO: 需要的话在这里处理 include
            return;
        }

        // 解析属性 @xxx（暂时丢弃）
        String rulePart = line;
        int attrIdx = line.indexOf('@');
        Set<String> attrs = new HashSet<>();
        if (attrIdx >= 0) {
            rulePart = line.substring(0, attrIdx).trim();
            String attrPart = line.substring(attrIdx + 1).trim();
            // 属性可以有多个 @a@b 这种，这里简单切分（你也可以改复杂点）
            if (!attrPart.isEmpty()) {
                for (String a : attrPart.split("@")) {
                    if (!a.isEmpty()) {
                        attrs.add(a);
                    }
                }
            }
        }

        if (rulePart.isEmpty()) {
            return;
        }

        // 判断前缀类型：domain/full/keyword/regexp...
        String type = "domain"; // 默认
        String value;
        int colonIdx = rulePart.indexOf(':');
        if (colonIdx > 0) {
            type = rulePart.substring(0, colonIdx).trim();
            value = rulePart.substring(colonIdx + 1).trim();
        } else {
            // 无前缀，按照 domain: 处理
            value = rulePart.trim();
        }

        if (value.isEmpty()) return;

        switch (type) {
            case "domain":
                trie.addDomain(value);
                break;
            case "full":
                trie.addFull(value);
                break;
            case "keyword":
            case "regexp":
                // 这里先忽略，有需要再单独维护列表
                break;
            default:
                // 其他类型暂时不支持
                break;
        }
    }

    private String stripComment(String line) {
        if (line == null) return "";
        // 官方规则里主要是 # 注释，也有可能 //，稳一点可以两个都截
        int hashIdx = line.indexOf('#');
        int slashIdx = line.indexOf("//");
        int cutIdx = -1;
        if (hashIdx >= 0) cutIdx = hashIdx;
        if (slashIdx >= 0) {
            if (cutIdx == -1 || slashIdx < cutIdx) {
                cutIdx = slashIdx;
            }
        }
        if (cutIdx >= 0) {
            return line.substring(0, cutIdx);
        }
        return line;
    }
}
