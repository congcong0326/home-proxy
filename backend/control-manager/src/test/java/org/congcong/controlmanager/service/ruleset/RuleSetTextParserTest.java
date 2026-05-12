package org.congcong.controlmanager.service.ruleset;

import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.enums.RuleSetItemType;
import org.congcong.controlmanager.dto.ruleset.RuleSetSourceFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RuleSetTextParser 单元测试")
class RuleSetTextParserTest {

    private final RuleSetTextParser parser = new RuleSetTextParser();

    @Test
    @DisplayName("解析 domain-list-community 规则")
    void testParseDomainListCommunity() {
        String content = """
                # comment
                domain:openai.com
                full:chat.openai.com @ads
                keyword:gpt
                include:ignored
                regexp:^.*$
                """;

        List<RuleSetItemDTO> items = parser.parse(content, RuleSetSourceFormat.DOMAIN_LIST_COMMUNITY);

        assertEquals(List.of(
                item(RuleSetItemType.DOMAIN, "chat.openai.com"),
                item(RuleSetItemType.DOMAIN_KEYWORD, "gpt"),
                item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com")
        ), items);
    }

    @Test
    @DisplayName("解析 clash classical 规则")
    void testParseClashClassical() {
        String content = """
                payload:
                  - DOMAIN-SUFFIX,OpenAI.com
                  - DOMAIN,chat.openai.com
                  - DOMAIN-KEYWORD,gpt
                  - '.claude.ai'
                """;

        List<RuleSetItemDTO> items = parser.parse(content, RuleSetSourceFormat.CLASH_CLASSICAL);

        assertEquals(List.of(
                item(RuleSetItemType.DOMAIN, "chat.openai.com"),
                item(RuleSetItemType.DOMAIN_KEYWORD, "gpt"),
                item(RuleSetItemType.DOMAIN_SUFFIX, "claude.ai"),
                item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com")
        ), items);
    }

    @Test
    @DisplayName("解析 plain domain list 并去重排序")
    void testParsePlainDomainListDeduplicateAndSort() {
        String content = """
                .example.com
                api.example.com
                +.example.com
                api.example.com
                """;

        List<RuleSetItemDTO> items = parser.parse(content, RuleSetSourceFormat.PLAIN_DOMAIN_LIST);

        assertEquals(List.of(
                item(RuleSetItemType.DOMAIN_SUFFIX, "api.example.com"),
                item(RuleSetItemType.DOMAIN_SUFFIX, "example.com")
        ), items);
    }

    private RuleSetItemDTO item(RuleSetItemType type, String value) {
        RuleSetItemDTO item = new RuleSetItemDTO();
        item.setType(type);
        item.setValue(value);
        return item;
    }
}
