package org.congcong.controlmanager.repository;

import org.congcong.common.enums.RuleSetSourceType;
import org.congcong.controlmanager.entity.RuleSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface RuleSetRepository extends JpaRepository<RuleSetEntity, Long>, JpaSpecificationExecutor<RuleSetEntity> {

    boolean existsByRuleKey(String ruleKey);

    boolean existsByRuleKeyAndIdNot(String ruleKey, Long id);

    boolean existsByRuleKeyAndEnabledTrueAndPublishedTrue(String ruleKey);

    List<RuleSetEntity> findByEnabledTrueAndPublishedTrueOrderByRuleKeyAsc();

    List<RuleSetEntity> findByIdInOrderByIdAsc(List<Long> ids);

    List<RuleSetEntity> findBySourceTypeNotOrderByIdAsc(RuleSetSourceType sourceType);

    List<RuleSetEntity> findBySourceTypeNotAndEnabledTrueOrderByIdAsc(RuleSetSourceType sourceType);

    List<RuleSetEntity> findBySourceTypeNotAndEnabledTrueAndPublishedTrueOrderByIdAsc(RuleSetSourceType sourceType);
}
