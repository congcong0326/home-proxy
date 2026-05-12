package org.congcong.controlmanager.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.congcong.common.dto.InboundConfigDTO;
import org.congcong.common.enums.RuleSetSourceType;
import org.congcong.controlmanager.dto.PageResponse;
import org.junit.jupiter.api.Test;

class ApiContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pageResponseUsesOneBasedItemsTotalPagePageSizeContract() throws Exception {
        PageResponse<String> response = new PageResponse<>(List.of("alpha"), 7, 2, 10);

        Map<?, ?> json = objectMapper.readValue(objectMapper.writeValueAsString(response), Map.class);

        assertEquals(List.of("alpha"), json.get("items"));
        assertEquals(7, json.get("total"));
        assertEquals(2, json.get("page"));
        assertEquals(10, json.get("pageSize"));
        assertFalse(json.containsKey("size"));
    }

    @Test
    void inboundConfigDtoDoesNotExposeLegacyUserAndRouteArrays() {
        Set<String> fields = Arrays.stream(InboundConfigDTO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertFalse(fields.contains("allowedUserIds"));
        assertFalse(fields.contains("routeIds"));
    }

    @Test
    void ruleSetSourceTypesDoNotExposeGitHubReleaseAsset() {
        Set<String> sourceTypes = Arrays.stream(RuleSetSourceType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertFalse(sourceTypes.contains("GITHUB_RELEASE_ASSET"));
    }
}
