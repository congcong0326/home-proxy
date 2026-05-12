package org.congcong.controlmanager.service.ruleset;

import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.enums.RuleSetItemType;
import org.congcong.controlmanager.dto.ruleset.RuleSetSourceFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class RuleSetTextParser {

    public List<RuleSetItemDTO> parse(String content, RuleSetSourceFormat format) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        Map<String, RuleSetItemDTO> dedup = new LinkedHashMap<>();
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            List<RuleSetItemDTO> parsedItems = switch (format) {
                case DOMAIN_LIST_COMMUNITY -> parseDomainListCommunityLine(rawLine);
                case CLASH_CLASSICAL -> parseClashClassicalLine(rawLine);
                case PLAIN_DOMAIN_LIST -> parsePlainDomainLine(rawLine);
            };
            for (RuleSetItemDTO item : parsedItems) {
                dedup.put(item.getType().name() + ":" + item.getValue(), item);
            }
        }

        return dedup.values().stream()
                .sorted(Comparator.comparing((RuleSetItemDTO item) -> item.getType().name())
                        .thenComparing(RuleSetItemDTO::getValue))
                .toList();
    }

    private List<RuleSetItemDTO> parseDomainListCommunityLine(String rawLine) {
        String line = stripComment(rawLine);
        if (line.isEmpty() || line.startsWith("include:")) {
            return List.of();
        }

        int attrIndex = line.indexOf('@');
        String rulePart = attrIndex >= 0 ? line.substring(0, attrIndex).trim() : line;
        if (rulePart.isEmpty()) {
            return List.of();
        }

        int colonIndex = rulePart.indexOf(':');
        String type = colonIndex > 0 ? rulePart.substring(0, colonIndex).trim().toLowerCase(Locale.ROOT) : "domain";
        String value = colonIndex > 0 ? rulePart.substring(colonIndex + 1).trim() : rulePart.trim();
        if (value.isEmpty()) {
            return List.of();
        }

        return switch (type) {
            case "domain" -> List.of(buildItem(RuleSetItemType.DOMAIN_SUFFIX, value));
            case "full" -> List.of(buildItem(RuleSetItemType.DOMAIN, value));
            case "keyword" -> List.of(buildItem(RuleSetItemType.DOMAIN_KEYWORD, value));
            case "regexp" -> List.of();
            default -> List.of();
        };
    }

    private List<RuleSetItemDTO> parseClashClassicalLine(String rawLine) {
        String line = stripComment(rawLine);
        if (line.isEmpty() || "payload:".equalsIgnoreCase(line)) {
            return List.of();
        }

        if (line.startsWith("-")) {
            line = line.substring(1).trim();
        }
        line = unwrapQuotes(line);
        if (line.isEmpty()) {
            return List.of();
        }

        if (line.startsWith("+.")) {
            return List.of(buildItem(RuleSetItemType.DOMAIN_SUFFIX, line.substring(2)));
        }
        if (line.startsWith(".")) {
            return List.of(buildItem(RuleSetItemType.DOMAIN_SUFFIX, line.substring(1)));
        }

        String[] parts = line.split(",", 3);
        if (parts.length >= 2) {
            String type = parts[0].trim().toUpperCase(Locale.ROOT);
            String value = parts[1].trim();
            return switch (type) {
                case "DOMAIN-SUFFIX" -> List.of(buildItem(RuleSetItemType.DOMAIN_SUFFIX, value));
                case "DOMAIN" -> List.of(buildItem(RuleSetItemType.DOMAIN, value));
                case "DOMAIN-KEYWORD" -> List.of(buildItem(RuleSetItemType.DOMAIN_KEYWORD, value));
                default -> List.of();
            };
        }

        return parsePlainDomainLine(line);
    }

    private List<RuleSetItemDTO> parsePlainDomainLine(String rawLine) {
        String line = stripComment(rawLine);
        if (line.isEmpty()) {
            return List.of();
        }

        if (line.startsWith("+.")) {
            return List.of(buildItem(RuleSetItemType.DOMAIN_SUFFIX, line.substring(2)));
        }
        if (line.startsWith(".")) {
            return List.of(buildItem(RuleSetItemType.DOMAIN_SUFFIX, line.substring(1)));
        }

        if (line.contains(",")) {
            return parseClashClassicalLine(line);
        }

        return List.of(buildItem(RuleSetItemType.DOMAIN_SUFFIX, line));
    }

    private RuleSetItemDTO buildItem(RuleSetItemType type, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则源中包含空规则项");
        }

        RuleSetItemDTO item = new RuleSetItemDTO();
        item.setType(type);
        item.setValue(normalized);
        return item;
    }

    private String stripComment(String rawLine) {
        if (rawLine == null) {
            return "";
        }
        String line = rawLine.trim();
        int hashIndex = line.indexOf('#');
        if (hashIndex >= 0) {
            line = line.substring(0, hashIndex).trim();
        }
        return line;
    }

    private String unwrapQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
