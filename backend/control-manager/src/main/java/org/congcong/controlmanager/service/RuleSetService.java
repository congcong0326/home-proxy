package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.RuleSetDTO;
import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.dto.RuleSetSummaryDTO;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.common.enums.RuleSetSourceType;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.dto.ruleset.RuleSetBatchSyncRequest;
import org.congcong.controlmanager.dto.ruleset.CreateRuleSetRequest;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncResultDTO;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncStatus;
import org.congcong.controlmanager.dto.ruleset.UpdateRuleSetRequest;
import org.congcong.controlmanager.entity.RuleSetEntity;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.service.ruleset.RuleSetSourceSyncService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuleSetService {

    private final RuleSetRepository ruleSetRepository;
    private final RuleSetSourceSyncService ruleSetSourceSyncService;

    public PageResponse<RuleSetSummaryDTO> getRuleSets(Pageable pageable, String name, RuleSetCategory category,
                                                       Boolean enabled, Boolean published) {
        Page<RuleSetSummaryDTO> page = ruleSetRepository.findPageSummaries(name, category, enabled, published, pageable);
        List<RuleSetSummaryDTO> items = page.getContent();
        return new PageResponse<>(items, page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }

    public RuleSetDTO getRuleSetById(Long id) {
        RuleSetEntity entity = ruleSetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则集不存在"));
        return convertToDTO(entity);
    }

    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RuleSetDTO createRuleSet(CreateRuleSetRequest request) {
        if (ruleSetRepository.existsByRuleKey(request.getRuleKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "规则集 key 已存在");
        }
        validateRuleSetRequest(request.getSourceType(), request.getSourceConfig(), request.getItems());
        RuleSetEntity entity = new RuleSetEntity();
        entity.setRuleKey(request.getRuleKey());
        entity.setName(request.getName());
        entity.setCategory(request.getCategory());
        entity.setMatchTarget(request.getMatchTarget());
        entity.setSourceType(request.getSourceType());
        entity.setSourceConfig(request.getSourceConfig());
        entity.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
        entity.setPublished(request.getPublished() == null ? Boolean.FALSE : request.getPublished());
        entity.setDescription(request.getDescription());
        entity.setItems(normalizeItems(request.getItems()));
        entity.setVersionNo(1L);
        return convertToDTO(ruleSetRepository.save(entity));
    }

    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RuleSetDTO updateRuleSet(Long id, UpdateRuleSetRequest request) {
        RuleSetEntity entity = ruleSetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则集不存在"));

        boolean contentChanged = false;
        RuleSetSourceType targetSourceType = request.getSourceType() == null ? entity.getSourceType() : request.getSourceType();
        String targetSourceConfig = request.getSourceConfig() == null ? entity.getSourceConfig() : request.getSourceConfig();
        List<RuleSetItemDTO> targetItems = request.getItems() == null ? entity.getItems() : request.getItems();
        validateRuleSetRequest(targetSourceType, targetSourceConfig, targetItems);

        if (request.getRuleKey() != null && !request.getRuleKey().equals(entity.getRuleKey())) {
            if (ruleSetRepository.existsByRuleKeyAndIdNot(request.getRuleKey(), id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "规则集 key 已存在");
            }
            entity.setRuleKey(request.getRuleKey());
        }
        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        if (request.getCategory() != null) {
            entity.setCategory(request.getCategory());
        }
        if (request.getMatchTarget() != null) {
            entity.setMatchTarget(request.getMatchTarget());
            contentChanged = true;
        }
        if (request.getSourceType() != null) {
            entity.setSourceType(request.getSourceType());
        }
        if (request.getSourceConfig() != null) {
            entity.setSourceConfig(request.getSourceConfig());
        }
        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
        }
        if (request.getPublished() != null) {
            entity.setPublished(request.getPublished());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (request.getItems() != null) {
            entity.setItems(normalizeItems(request.getItems()));
            contentChanged = true;
        }
        if (contentChanged) {
            entity.setVersionNo(entity.getVersionNo() == null ? 1L : entity.getVersionNo() + 1);
        }
        return convertToDTO(ruleSetRepository.save(entity));
    }

    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RuleSetDTO syncRuleSet(Long id) {
        RuleSetEntity entity = ruleSetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则集不存在"));
        return convertToDTO(syncRuleSetEntity(entity).entity());
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public List<RuleSetSyncResultDTO> syncRuleSets(RuleSetBatchSyncRequest request) {
        List<RuleSetEntity> entities = resolveSyncTargets(request);
        return entities.stream()
                .map(this::syncRuleSetSafely)
                .toList();
    }

    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteRuleSet(Long id) {
        RuleSetEntity entity = ruleSetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则集不存在"));
        ruleSetRepository.delete(entity);
    }

    public List<RuleSetDTO> getPublishedRuleSets() {
        return ruleSetRepository.findByEnabledTrueAndPublishedTrueOrderByRuleKeyAsc()
                .stream()
                .map(this::convertToDTO)
                .toList();
    }

    private RuleSetDTO convertToDTO(RuleSetEntity entity) {
        RuleSetDTO dto = new RuleSetDTO();
        dto.setId(entity.getId());
        dto.setRuleKey(entity.getRuleKey());
        dto.setName(entity.getName());
        dto.setCategory(entity.getCategory());
        dto.setMatchTarget(entity.getMatchTarget());
        dto.setSourceType(entity.getSourceType());
        dto.setSourceConfig(entity.getSourceConfig());
        dto.setEnabled(entity.getEnabled());
        dto.setPublished(entity.getPublished());
        dto.setVersionNo(entity.getVersionNo());
        dto.setDescription(entity.getDescription());
        dto.setItems(copyItems(entity.getItems()));
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private List<RuleSetItemDTO> copyItems(List<RuleSetItemDTO> items) {
        if (items == null) {
            return new ArrayList<>();
        }
        return items.stream().map(item -> {
            RuleSetItemDTO copy = new RuleSetItemDTO();
            copy.setType(item.getType());
            copy.setValue(item.getValue() == null ? null : item.getValue().trim());
            return copy;
        }).toList();
    }

    private List<RuleSetItemDTO> normalizeItems(List<RuleSetItemDTO> items) {
        return copyItems(items).stream()
                .sorted((left, right) -> {
                    String leftKey = (left.getType() == null ? "" : left.getType().name()) + ":" + safeValue(left.getValue());
                    String rightKey = (right.getType() == null ? "" : right.getType().name()) + ":" + safeValue(right.getValue());
                    return leftKey.compareTo(rightKey);
                })
                .toList();
    }

    private void validateRuleSetRequest(RuleSetSourceType sourceType, String sourceConfig, List<RuleSetItemDTO> items) {
        if (sourceType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源类型不能为空");
        }

        List<RuleSetItemDTO> normalizedItems = copyItems(items);
        validateItems(normalizedItems);

        if (sourceType == RuleSetSourceType.MANUAL && normalizedItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MANUAL 规则集必须至少包含一条规则项");
        }
        if (sourceType != RuleSetSourceType.MANUAL && (sourceConfig == null || sourceConfig.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "外部规则集必须配置 sourceConfig");
        }
        if (sourceType != RuleSetSourceType.MANUAL) {
            ruleSetSourceSyncService.parseSourceConfig(sourceConfig, sourceType);
        }
    }

    private void validateItems(List<RuleSetItemDTO> items) {
        for (RuleSetItemDTO item : items) {
            if (item.getType() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则项 type 不能为空");
            }
            if (item.getValue() == null || item.getValue().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则项 value 不能为空");
            }
        }
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private List<RuleSetEntity> resolveSyncTargets(RuleSetBatchSyncRequest request) {
        List<Long> ids = request == null ? null : request.getRuleSetIds();
        boolean enabledOnly = request == null || request.getEnabledOnly() == null || request.getEnabledOnly();
        boolean publishedOnly = request != null && Boolean.TRUE.equals(request.getPublishedOnly());

        if (ids != null && !ids.isEmpty()) {
            List<RuleSetEntity> entities = ruleSetRepository.findByIdInOrderByIdAsc(ids);
            Set<Long> foundIds = new HashSet<>();
            entities.forEach(entity -> foundIds.add(entity.getId()));
            List<Long> missingIds = ids.stream()
                    .filter(id -> !foundIds.contains(id))
                    .toList();
            if (!missingIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部分规则集不存在: " + missingIds);
            }
            return entities;
        }

        if (publishedOnly) {
            return ruleSetRepository.findBySourceTypeNotAndEnabledTrueAndPublishedTrueOrderByIdAsc(RuleSetSourceType.MANUAL);
        }
        if (enabledOnly) {
            return ruleSetRepository.findBySourceTypeNotAndEnabledTrueOrderByIdAsc(RuleSetSourceType.MANUAL);
        }
        return ruleSetRepository.findBySourceTypeNotOrderByIdAsc(RuleSetSourceType.MANUAL);
    }

    private RuleSetSyncResultDTO syncRuleSetSafely(RuleSetEntity entity) {
        if (entity.getSourceType() == RuleSetSourceType.MANUAL) {
            return toSyncResult(entity, RuleSetSyncStatus.SKIPPED, "MANUAL 规则集不支持同步");
        }
        try {
            SyncOutcome outcome = syncRuleSetEntity(entity);
            return toSyncResult(outcome.entity(), outcome.changed() ? RuleSetSyncStatus.UPDATED : RuleSetSyncStatus.UNCHANGED, null);
        } catch (ResponseStatusException ex) {
            return toSyncResult(entity, RuleSetSyncStatus.FAILED, ex.getReason());
        } catch (Exception ex) {
            return toSyncResult(entity, RuleSetSyncStatus.FAILED, ex.getMessage());
        }
    }

    private SyncOutcome syncRuleSetEntity(RuleSetEntity entity) {
        List<RuleSetItemDTO> syncedItems = ruleSetSourceSyncService.sync(entity);
        List<RuleSetItemDTO> normalizedItems = normalizeItems(syncedItems);
        boolean changed = !normalizedItems.equals(normalizeItems(entity.getItems()));
        if (changed) {
            entity.setItems(normalizedItems);
            entity.setVersionNo(entity.getVersionNo() == null ? 1L : entity.getVersionNo() + 1);
            entity = ruleSetRepository.save(entity);
        }
        return new SyncOutcome(entity, changed);
    }

    private RuleSetSyncResultDTO toSyncResult(RuleSetEntity entity, RuleSetSyncStatus status, String message) {
        RuleSetSyncResultDTO dto = new RuleSetSyncResultDTO();
        dto.setRuleSetId(entity.getId());
        dto.setRuleKey(entity.getRuleKey());
        dto.setName(entity.getName());
        dto.setStatus(status);
        dto.setVersionNo(entity.getVersionNo());
        dto.setItemCount(entity.getItems() == null ? 0 : entity.getItems().size());
        dto.setMessage(message);
        return dto;
    }

    private record SyncOutcome(RuleSetEntity entity, boolean changed) {
    }
}
