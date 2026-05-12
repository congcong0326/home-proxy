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
import org.congcong.controlmanager.entity.RuleSetPayloadEntity;
import org.congcong.controlmanager.repository.RuleSetPayloadRepository;
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
import org.springframework.data.jpa.domain.Specification;
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
    private RuleSetPayloadRepository ruleSetPayloadRepository;

    @Mock
    private RuleSetSourceSyncService ruleSetSourceSyncService;

    @InjectMocks
    private RuleSetService ruleSetService;

    @Test
    @DisplayName("分页查询规则集摘要时不返回完整规则项")
    void testGetRuleSetsReturnsSummaries() {
        RuleSetEntity summary = new RuleSetEntity();
        summary.setId(1L);
        summary.setRuleKey("geo-not-cn");
        summary.setName("Geo Not CN");
        summary.setCategory(RuleSetCategory.GEO);
        summary.setMatchTarget(RuleSetMatchTarget.DOMAIN);
        summary.setSourceType(RuleSetSourceType.GIT_RAW_FILE);
        summary.setSourceConfig("{\"url\":\"https://example.com/geo.txt\"}");
        summary.setEnabled(true);
        summary.setPublished(true);
        summary.setVersionNo(3L);
        summary.setDescription("geo summary");
        summary.setItemCount(2048);

        when(ruleSetRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1));

        var result = ruleSetService.getRuleSets(PageRequest.of(0, 10), "geo", RuleSetCategory.GEO, true, true);

        assertEquals(1, result.getItems().size());
        assertEquals("geo-not-cn", result.getItems().get(0).getRuleKey());
        assertEquals(2048, result.getItems().get(0).getItemCount());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getPageSize());
        assertEquals(1, result.getTotal());
        verify(ruleSetRepository).findAll(any(Specification.class), any(PageRequest.class));
        verify(ruleSetRepository, never()).findById(any());
        assertFalse(result.getItems().isEmpty());
    }

    @Test
    @DisplayName("查询规则集配置详情时不加载规则项")
    void testGetRuleSetByIdReturnsSummaryWithoutItems() {
        RuleSetEntity entity = externalEntity(1L, "geo-not-cn", List.of(item(RuleSetItemType.DOMAIN_SUFFIX, "example.com")), true);
        entity.setDescription("geo detail");

        when(ruleSetRepository.findById(1L)).thenReturn(Optional.of(entity));

        RuleSetSummaryDTO result = ruleSetService.getRuleSetById(1L);

        assertEquals("geo-not-cn", result.getRuleKey());
        assertEquals(1, result.getItemCount());
        verify(ruleSetRepository).findById(1L);
        verify(ruleSetPayloadRepository, never()).findByRuleSetIdIn(any());
    }

    @Test
    @DisplayName("分页查询规则项")
    void testGetRuleSetItemsReturnsPagedItems() {
        RuleSetEntity entity = externalEntity(1L, "ai-openai", List.of(), true);
        List<RuleSetItemDTO> items = List.of(
                item(RuleSetItemType.DOMAIN, "chat.openai.com"),
                item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com"),
                item(RuleSetItemType.DOMAIN_KEYWORD, "gpt")
        );

        when(ruleSetRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(ruleSetPayloadRepository.findByRuleSetIdIn(List.of(1L)))
                .thenReturn(List.of(payload(1L, items)));

        var result = ruleSetService.getRuleSetItems(1L, PageRequest.of(1, 2));

        assertEquals(2, result.getPage());
        assertEquals(2, result.getPageSize());
        assertEquals(3, result.getTotal());
        assertEquals(List.of(items.get(2)), result.getItems());
    }

    @Test
    @DisplayName("查询已发布规则集摘要时不加载规则项")
    void testGetPublishedRuleSetsReturnsSummariesWithoutItems() {
        RuleSetEntity entity = externalEntity(1L, "ai-openai", List.of(item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com")), true);
        entity.setPublished(true);

        when(ruleSetRepository.findByEnabledTrueAndPublishedTrueOrderByRuleKeyAsc()).thenReturn(List.of(entity));

        List<RuleSetSummaryDTO> result = ruleSetService.getPublishedRuleSets();

        assertEquals(1, result.size());
        assertEquals("ai-openai", result.get(0).getRuleKey());
        verify(ruleSetPayloadRepository, never()).findByRuleSetIdIn(any());
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
        when(ruleSetPayloadRepository.save(any(RuleSetPayloadEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
        when(ruleSetPayloadRepository.findByRuleSetIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(payload(1L, List.of(item(RuleSetItemType.DOMAIN_SUFFIX, "old.com"))),
                        payload(2L, List.of(item(RuleSetItemType.DOMAIN_SUFFIX, "manual.example")))));
        when(ruleSetSourceSyncService.sync(external)).thenReturn(List.of(
                item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com"),
                item(RuleSetItemType.DOMAIN, "chat.openai.com")
        ));
        when(ruleSetRepository.save(any(RuleSetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ruleSetPayloadRepository.save(any(RuleSetPayloadEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
        when(ruleSetPayloadRepository.findByRuleSetIdIn(List.of(1L)))
                .thenReturn(List.of(payload(1L, List.of(
                        item(RuleSetItemType.DOMAIN, "chat.openai.com"),
                        item(RuleSetItemType.DOMAIN_SUFFIX, "openai.com")
                ))));
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
        entity.setItemCount(items.size());
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
        entity.setItemCount(1);
        return entity;
    }

    private RuleSetPayloadEntity payload(Long ruleSetId, List<RuleSetItemDTO> items) {
        RuleSetPayloadEntity payload = new RuleSetPayloadEntity();
        payload.setRuleSetId(ruleSetId);
        payload.setItems(items);
        return payload;
    }

    private RuleSetItemDTO item(RuleSetItemType type, String value) {
        RuleSetItemDTO item = new RuleSetItemDTO();
        item.setType(type);
        item.setValue(value);
        return item;
    }
}
