package org.congcong.controlmanager.service.ruleset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.enums.RuleSetMatchTarget;
import org.congcong.common.enums.RuleSetSourceType;
import org.congcong.controlmanager.dto.ruleset.RuleSetSourceConfig;
import org.congcong.controlmanager.dto.ruleset.RuleSetSourceFormat;
import org.congcong.controlmanager.entity.RuleSetEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuleSetSourceSyncService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper objectMapper;
    private final RuleSetTextParser ruleSetTextParser;

    public List<RuleSetItemDTO> sync(RuleSetEntity entity) {
        if (entity.getSourceType() == RuleSetSourceType.MANUAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MANUAL 规则集不支持同步，请直接编辑规则项");
        }
        if (entity.getMatchTarget() != RuleSetMatchTarget.DOMAIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前仅支持 DOMAIN 类型规则集同步");
        }

        RuleSetSourceConfig sourceConfig = parseSourceConfig(entity.getSourceConfig(), entity.getSourceType());
        String content = downloadContent(sourceConfig.getUrl());
        List<RuleSetItemDTO> items = ruleSetTextParser.parse(content, sourceConfig.getFormat());
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同步完成，但未解析出任何规则项");
        }
        return items;
    }

    public RuleSetSourceConfig parseSourceConfig(String rawConfig, RuleSetSourceType sourceType) {
        if (rawConfig == null || rawConfig.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "外部规则集必须提供 sourceConfig");
        }

        String trimmed = rawConfig.trim();
        RuleSetSourceConfig config;
        if (trimmed.startsWith("{")) {
            config = parseJsonConfig(trimmed);
        } else {
            config = new RuleSetSourceConfig();
            config.setUrl(trimmed);
        }

        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceConfig.url 不能为空");
        }

        validateUrl(config.getUrl(), sourceType);
        if (config.getFormat() == null) {
            config.setFormat(inferFormat(config.getUrl()));
        }
        return config;
    }

    private RuleSetSourceConfig parseJsonConfig(String rawConfig) {
        try {
            return objectMapper.readValue(rawConfig, RuleSetSourceConfig.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceConfig JSON 格式不正确");
        }
    }

    private void validateUrl(String url, RuleSetSourceType sourceType) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceConfig.url 必须是 http 或 https 地址");
            }
            if (sourceType == RuleSetSourceType.GIT_RAW_FILE && uri.getHost() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GIT_RAW_FILE 规则集必须配置有效的 raw 文件地址");
            }
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceConfig.url 格式不正确");
        }
    }

    private RuleSetSourceFormat inferFormat(String url) {
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.endsWith(".yaml") || lowerUrl.endsWith(".yml")) {
            return RuleSetSourceFormat.CLASH_CLASSICAL;
        }
        return RuleSetSourceFormat.DOMAIN_LIST_COMMUNITY;
    }

    private String downloadContent(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "nas-proxy-rule-set-sync/1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "规则源下载失败，HTTP 状态码: " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "规则源下载失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "规则源下载被中断");
        }
    }
}
