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
import org.congcong.controlmanager.entity.RuleSetPayloadEntity;
import org.congcong.controlmanager.repository.RuleSetPayloadRepository;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.service.ruleset.RuleSetSourceSyncService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RuleSetService {

    private final RuleSetRepository ruleSetRepository;
    private final RuleSetPayloadRepository ruleSetPayloadRepository;
    private final RuleSetSourceSyncService ruleSetSourceSyncService;

    public PageResponse<RuleSetSummaryDTO> getRuleSets(Pageable pageable, String name, RuleSetCategory category,
                                                       Boolean enabled, Boolean published) {
        Page<RuleSetEntity> page = ruleSetRepository.findAll(buildSummarySpecification(name, category, enabled, published),
                sanitizePageable(pageable));
        List<RuleSetSummaryDTO> items = page.getContent().stream()
                .map(this::convertToSummaryDTO)
                .toList();
        return new PageResponse<>(items, page.getTotalElements(), page.getNumber() + 1, page.getSize());
    }

    public RuleSetSummaryDTO getRuleSetById(Long id) {
        RuleSetEntity entity = ruleSetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则集不存在"));
        return convertToSummaryDTO(entity);
    }

    public PageResponse<RuleSetItemDTO> getRuleSetItems(Long id, Pageable pageable) {
        RuleSetEntity entity = ruleSetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则集不存在"));
        Pageable sanitizedPageable = sanitizeItemsPageable(pageable);
        List<RuleSetItemDTO> items = loadItemsForIds(List.of(id)).getOrDefault(entity.getId(), List.of());
        int fromIndex = Math.min((int) sanitizedPageable.getOffset(), items.size());
        int toIndex = Math.min(fromIndex + sanitizedPageable.getPageSize(), items.size());
        return new PageResponse<>(
                items.subList(fromIndex, toIndex),
                items.size(),
                sanitizedPageable.getPageNumber() + 1,
                sanitizedPageable.getPageSize()
        );
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RuleSetDTO createRuleSet(CreateRuleSetRequest request) {
        if (ruleSetRepository.existsByRuleKey(request.getRuleKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "规则集 key 已存在");
        }
        validateRuleSetRequest(request.getSourceType(), request.getSourceConfig(), request.getItems());
        List<RuleSetItemDTO> normalizedItems = normalizeItems(request.getItems());
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
        applyItems(entity, normalizedItems);
        entity.setVersionNo(1L);
        entity = ruleSetRepository.save(entity);
        saveItems(entity);
        return convertToDTO(entity);
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RuleSetDTO updateRuleSet(Long id, UpdateRuleSetRequest request) {
        RuleSetEntity entity = ruleSetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则集不存在"));
        loadItems(entity);

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
            applyItems(entity, normalizeItems(request.getItems()));
            contentChanged = true;
        }
        if (contentChanged) {
            entity.setVersionNo(entity.getVersionNo() == null ? 1L : entity.getVersionNo() + 1);
        }
        entity = ruleSetRepository.save(entity);
        saveItems(entity);
        return convertToDTO(entity);
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RuleSetDTO syncRuleSet(Long id) {
        RuleSetEntity entity = ruleSetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则集不存在"));
        loadItems(entity);
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


    public List<RuleSetSummaryDTO> getPublishedRuleSets() {
        List<RuleSetEntity> entities = ruleSetRepository.findByEnabledTrueAndPublishedTrueOrderByRuleKeyAsc();
        return entities
                .stream()
                .map(this::convertToSummaryDTO)
                .toList();
    }


    public List<RuleSetDTO> getPublishedRuleSetsWithItems() {
        List<RuleSetEntity> entities = ruleSetRepository.findByEnabledTrueAndPublishedTrueOrderByRuleKeyAsc();
        loadItems(entities);
        return entities
                .stream()
                .map(this::convertToDTO)
                .toList();
    }

    private RuleSetSummaryDTO convertToSummaryDTO(RuleSetEntity entity) {
        return new RuleSetSummaryDTO(
                entity.getId(),
                entity.getRuleKey(),
                entity.getName(),
                entity.getCategory(),
                entity.getMatchTarget(),
                entity.getSourceType(),
                entity.getSourceConfig(),
                entity.getEnabled(),
                entity.getPublished(),
                entity.getVersionNo(),
                entity.getDescription(),
                entity.getItemCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
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

    private void applyItems(RuleSetEntity entity, List<RuleSetItemDTO> items) {
        List<RuleSetItemDTO> copiedItems = copyItems(items);
        entity.setItems(copiedItems);
        entity.setItemCount(copiedItems.size());
    }

    private void loadItems(RuleSetEntity entity) {
        if (entity == null) {
            return;
        }
        applyItems(entity, loadItemsForIds(List.of(entity.getId())).getOrDefault(entity.getId(), List.of()));
    }

    private void loadItems(List<RuleSetEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        Map<Long, List<RuleSetItemDTO>> payloadMap = loadItemsForIds(entities.stream()
                .map(RuleSetEntity::getId)
                .filter(Objects::nonNull)
                .toList());
        entities.forEach(entity -> applyItems(entity, payloadMap.getOrDefault(entity.getId(), List.of())));
    }

    private Map<Long, List<RuleSetItemDTO>> loadItemsForIds(List<Long> ruleSetIds) {
        if (ruleSetIds == null || ruleSetIds.isEmpty()) {
            return Map.of();
        }
        return ruleSetPayloadRepository.findByRuleSetIdIn(ruleSetIds).stream()
                .collect(Collectors.toMap(RuleSetPayloadEntity::getRuleSetId,
                        payload -> copyItems(payload.getItems())));
    }

    private void saveItems(RuleSetEntity entity) {
        RuleSetPayloadEntity payload = new RuleSetPayloadEntity();
        payload.setRuleSetId(entity.getId());
        payload.setItems(copyItems(entity.getItems()));
        ruleSetPayloadRepository.save(payload);
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

    private Specification<RuleSetEntity> buildSummarySpecification(String name, RuleSetCategory category,
                                                                   Boolean enabled, Boolean published) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (name != null && !name.isBlank()) {
                String pattern = "%" + name.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("ruleKey")), pattern)
                ));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            if (published != null) {
                predicates.add(cb.equal(root.get("published"), published));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private Pageable sanitizePageable(Pageable pageable) {
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Direction.DESC, "updatedAt");
        List<Sort.Order> supportedOrders = sort.stream()
                .map(this::sanitizeSortOrder)
                .toList();
        if (supportedOrders.isEmpty()) {
            supportedOrders = List.of(new Sort.Order(Sort.Direction.DESC, "updatedAt"));
        }
        return org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(supportedOrders));
    }

    private Pageable sanitizeItemsPageable(Pageable pageable) {
        int pageNumber = Math.max(pageable.getPageNumber(), 0);
        int pageSize = pageable.getPageSize() <= 0 ? 50 : Math.min(pageable.getPageSize(), 500);
        return org.springframework.data.domain.PageRequest.of(pageNumber, pageSize);
    }

    private Sort.Order sanitizeSortOrder(Sort.Order order) {
        String property = switch (order.getProperty()) {
            case "id", "ruleKey", "name", "category", "matchTarget", "sourceType",
                    "enabled", "published", "versionNo", "itemCount", "createdAt", "updatedAt" -> order.getProperty();
            default -> "updatedAt";
        };
        return new Sort.Order(order.getDirection(), property);
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
            loadItems(entities);
            return entities;
        }

        if (publishedOnly) {
            List<RuleSetEntity> entities = ruleSetRepository.findBySourceTypeNotAndEnabledTrueAndPublishedTrueOrderByIdAsc(RuleSetSourceType.MANUAL);
            loadItems(entities);
            return entities;
        }
        if (enabledOnly) {
            List<RuleSetEntity> entities = ruleSetRepository.findBySourceTypeNotAndEnabledTrueOrderByIdAsc(RuleSetSourceType.MANUAL);
            loadItems(entities);
            return entities;
        }
        List<RuleSetEntity> entities = ruleSetRepository.findBySourceTypeNotOrderByIdAsc(RuleSetSourceType.MANUAL);
        loadItems(entities);
        return entities;
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
            applyItems(entity, normalizedItems);
            entity.setVersionNo(entity.getVersionNo() == null ? 1L : entity.getVersionNo() + 1);
            entity = ruleSetRepository.save(entity);
            saveItems(entity);
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
        dto.setItemCount(entity.getItemCount() == null ? 0 : entity.getItemCount());
        dto.setMessage(message);
        return dto;
    }

    private record SyncOutcome(RuleSetEntity entity, boolean changed) {
    }
}
