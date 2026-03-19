package org.congcong.controlmanager.repository;

import org.congcong.common.dto.RuleSetSummaryDTO;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.common.enums.RuleSetSourceType;
import org.congcong.controlmanager.entity.RuleSetEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RuleSetRepository extends JpaRepository<RuleSetEntity, Long> {

    boolean existsByRuleKey(String ruleKey);

    boolean existsByRuleKeyAndIdNot(String ruleKey, Long id);

    boolean existsByRuleKeyAndEnabledTrueAndPublishedTrue(String ruleKey);

    List<RuleSetEntity> findByEnabledTrueAndPublishedTrueOrderByRuleKeyAsc();

    List<RuleSetEntity> findByIdInOrderByIdAsc(List<Long> ids);

    List<RuleSetEntity> findBySourceTypeNotOrderByIdAsc(RuleSetSourceType sourceType);

    List<RuleSetEntity> findBySourceTypeNotAndEnabledTrueOrderByIdAsc(RuleSetSourceType sourceType);

    List<RuleSetEntity> findBySourceTypeNotAndEnabledTrueAndPublishedTrueOrderByIdAsc(RuleSetSourceType sourceType);

    @Query("SELECT new org.congcong.common.dto.RuleSetSummaryDTO(" +
            "r.id, r.ruleKey, r.name, r.category, r.matchTarget, r.sourceType, r.sourceConfig, " +
            "r.enabled, r.published, r.versionNo, r.description, r.itemCount, r.createdAt, r.updatedAt) " +
            "FROM RuleSetEntity r WHERE " +
            "(:name IS NULL OR LOWER(r.name) LIKE LOWER(CONCAT('%', :name, '%')) OR LOWER(r.ruleKey) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
            "(:category IS NULL OR r.category = :category) AND " +
            "(:enabled IS NULL OR r.enabled = :enabled) AND " +
            "(:published IS NULL OR r.published = :published)")
    Page<RuleSetSummaryDTO> findPageSummaries(@Param("name") String name,
                                              @Param("category") RuleSetCategory category,
                                              @Param("enabled") Boolean enabled,
                                              @Param("published") Boolean published,
                                              Pageable pageable);
}
