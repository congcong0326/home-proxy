package org.congcong.controlmanager.service;

import org.congcong.common.dto.RuleSetDTO;
import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.dto.RuleSetSummaryDTO;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.common.enums.RuleSetItemType;
import org.congcong.common.enums.RuleSetMatchTarget;
import org.congcong.common.enums.RuleSetSourceType;
import org.congcong.controlmanager.dto.ruleset.CreateRuleSetRequest;
import org.congcong.controlmanager.dto.ruleset.RuleSetBatchSyncRequest;
import org.congcong.controlmanager.dto.ruleset.RuleSetSourceConfig;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncResultDTO;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncStatus;
import org.congcong.controlmanager.entity.RuleSetEntity;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.service.ruleset.RuleSetSourceSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RuleSetService 单元测试")
class RuleSetServiceTest {

    @Mock
    private RuleSetRepository ruleSetRepository;

    @Mock
    private RuleSetSourceSyncService ruleSetSourceSyncService;

    @InjectMocks
    private RuleSetService ruleSetService;

    @Test
    @DisplayName("分页查询规则集摘要时不返回完整规则项")
    void testGetRuleSetsReturnsSummaries() {
        RuleSetSummaryDTO summary = new RuleSetSummaryDTO(
                1L,
                "geo-not-cn",
                "Geo Not CN",
                RuleSetCategory.GEO,
                RuleSetMatchTarget.DOMAIN,
                RuleSetSourceType.GIT_RAW_FILE,
                "{\"url\":\"https://example.com/geo.txt\"}",
                true,
                true,
                3L,
                "geo summary",
                2048,
                null,
                null
        );

        when(ruleSetRepository.findPageSummaries(eq("geo"), eq(RuleSetCategory.GEO), eq(true), eq(true), any()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1));

        var result = ruleSetService.getRuleSets(PageRequest.of(0, 10), "geo", RuleSetCategory.GEO, true, true);

        assertEquals(1, result.getItems().size());
        assertEquals("geo-not-cn", result.getItems().get(0).getRuleKey());
        assertEquals(2048, result.getItems().get(0).getItemCount());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals(1, result.getTotal());
        verify(ruleSetRepository).findPageSummaries("geo", RuleSetCategory.GEO, true, true, PageRequest.of(0, 10));
        verify(ruleSetRepository, never()).findById(any());
        assertFalse(result.getItems().isEmpty());
    }

    @Test
    @DisplayName("创建外部规则集允许空 items，但必须校验 sourceConfig")
    void testCreateExternalRuleSetWithEmptyItemsSuccess() {
        CreateRuleSetRequest request = new CreateRuleSetRequest();
        request.setRuleKey("ai-openai");
        request.setName("AI OpenAI");
        request.setCategory(RuleSetCategory.AI);
        request.setMatchTarget(RuleSetMatchTarget.DOMAIN);
        request.setSourceType(RuleSetSourceType.HTTP_FILE);
        request.setSourceConfig("{\"url\":\"https://example.com/openai.txt\"}");
        request.setEnabled(true);
        request.setPublished(false);
        request.setItems(List.of());

        RuleSetEntity saved = externalEntity(1L, "ai-openai", List.of(), true);

        when(ruleSetRepository.existsByRuleKey("ai-openai")).thenReturn(false);
        when(ruleSetSourceSyncService.parseSourceConfig(eq(request.getSourceConfig()), eq(RuleSetSourceType.HTTP_FILE)))
                .thenReturn(new RuleSetSourceConfig());
        when(ruleSetRepository.save(any(RuleSetEntity.class))).thenReturn(saved);

        RuleSetDTO result = ruleSetService.createRuleSet(request);

        assertEquals("ai-openai", result.getRuleKey());
        assertEquals(0, result.getItems().size());
        verify(ruleSetSourceSyncService).parseSourceConfig(request.getSourceConfig(), RuleSetSourceType.HTTP_FILE);
    }

    @Test
    @DisplayName("批量同步按 id 返回 UPDATED 和 SKIPPED")
    void testSyncRuleSetsByIdsUpdatedAndSkipped() {
        RuleSetEntity external = externalEntity(1L, "ai-openai", List.of(item(RuleSetItemType.DOMAIN_SUFFIX, "old.com")), true);
        external.setVersionNo(1L);
        RuleSetEntity manual = manualEntity(2L, "ai-manual");

        when(ruleSetRepository.findByIdInOrderByIdAsc(List.of(1L, 2L))).thenReturn(List.of(external, manual));
        when(ruleSetSourceSyncService.sync(external)).thenReturn(List.of(
                item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com"),
                item(RuleSetItemType.DOMAIN, "chat.openai.com")
        ));
        when(ruleSetRepository.save(any(RuleSetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RuleSetBatchSyncRequest request = new RuleSetBatchSyncRequest();
        request.setRuleSetIds(List.of(1L, 2L));

        List<RuleSetSyncResultDTO> results = ruleSetService.syncRuleSets(request);

        assertEquals(2, results.size());
        assertEquals(RuleSetSyncStatus.UPDATED, results.get(0).getStatus());
        assertEquals(2L, results.get(0).getVersionNo());
        assertEquals(2, results.get(0).getItemCount());
        assertEquals(RuleSetSyncStatus.SKIPPED, results.get(1).getStatus());
        assertTrue(results.get(1).getMessage().contains("MANUAL"));

        ArgumentCaptor<RuleSetEntity> captor = ArgumentCaptor.forClass(RuleSetEntity.class);
        verify(ruleSetRepository).save(captor.capture());
        assertEquals(2L, captor.getValue().getVersionNo());
        assertEquals(List.of(
                item(RuleSetItemType.DOMAIN, "chat.openai.com"),
                item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com")
        ), captor.getValue().getItems());
    }

    @Test
    @DisplayName("批量同步缺失规则集 id 时报错")
    void testSyncRuleSetsMissingIdsFail() {
        RuleSetBatchSyncRequest request = new RuleSetBatchSyncRequest();
        request.setRuleSetIds(List.of(1L, 2L));

        when(ruleSetRepository.findByIdInOrderByIdAsc(List.of(1L, 2L))).thenReturn(List.of(externalEntity(1L, "ai-openai", List.of(), true)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> ruleSetService.syncRuleSets(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("部分规则集不存在"));
        verify(ruleSetRepository, never()).save(any(RuleSetEntity.class));
    }

    @Test
    @DisplayName("单条同步内容未变化时不保存")
    void testSyncRuleSetUnchangedDoesNotSave() {
        RuleSetEntity entity = externalEntity(1L, "ai-openai", List.of(
                item(RuleSetItemType.DOMAIN, "chat.openai.com"),
                item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com")
        ), true);
        entity.setVersionNo(3L);

        when(ruleSetRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(ruleSetSourceSyncService.sync(entity)).thenReturn(List.of(
                item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com"),
                item(RuleSetItemType.DOMAIN, "chat.openai.com")
        ));

        RuleSetDTO result = ruleSetService.syncRuleSet(1L);

        assertEquals(3L, result.getVersionNo());
        assertEquals(2, result.getItems().size());
        verify(ruleSetRepository, never()).save(any(RuleSetEntity.class));
    }

    private RuleSetEntity externalEntity(Long id, String ruleKey, List<RuleSetItemDTO> items, boolean enabled) {
        RuleSetEntity entity = new RuleSetEntity();
        entity.setId(id);
        entity.setRuleKey(ruleKey);
        entity.setName(ruleKey);
        entity.setCategory(RuleSetCategory.AI);
        entity.setMatchTarget(RuleSetMatchTarget.DOMAIN);
        entity.setSourceType(RuleSetSourceType.HTTP_FILE);
        entity.setSourceConfig("{\"url\":\"https://example.com/" + ruleKey + ".txt\"}");
        entity.setEnabled(enabled);
        entity.setPublished(false);
        entity.setVersionNo(1L);
        entity.setItems(items);
        return entity;
    }

    private RuleSetEntity manualEntity(Long id, String ruleKey) {
        RuleSetEntity entity = new RuleSetEntity();
        entity.setId(id);
        entity.setRuleKey(ruleKey);
        entity.setName(ruleKey);
        entity.setCategory(RuleSetCategory.AI);
        entity.setMatchTarget(RuleSetMatchTarget.DOMAIN);
        entity.setSourceType(RuleSetSourceType.MANUAL);
        entity.setEnabled(true);
        entity.setPublished(true);
        entity.setVersionNo(1L);
        entity.setItems(List.of(item(RuleSetItemType.DOMAIN_SUFFIX, "manual.example")));
        return entity;
    }

    private RuleSetItemDTO item(RuleSetItemType type, String value) {
        RuleSetItemDTO item = new RuleSetItemDTO();
        item.setType(type);
        item.setValue(value);
        return item;
    }
}
