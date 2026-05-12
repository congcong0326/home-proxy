package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.RuleSetPayloadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RuleSetPayloadRepository extends JpaRepository<RuleSetPayloadEntity, Long> {

    List<RuleSetPayloadEntity> findByRuleSetIdIn(Collection<Long> ruleSetIds);
}
