package org.congcong.controlmanager.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.dto.InboundConfigDTO;
import org.congcong.common.dto.RateLimitDTO;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.RuleSetDTO;
import org.congcong.common.dto.UserDtoWithCredential;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class CanonicalConfigHasher {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public String hash(List<InboundConfigDTO> inbounds, List<RouteDTO> routes, List<RateLimitDTO> rateLimits,
                       List<UserDtoWithCredential> users, List<RuleSetDTO> ruleSets) {
        try {
            LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("inbounds", inbounds);
            snapshot.put("routes", routes);
            snapshot.put("rateLimits", rateLimits);
            snapshot.put("users", users);
            snapshot.put("ruleSets", ruleSets);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(snapshot)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算配置哈希", e);
        }
    }
}
