package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.AccessLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccessLogRepository extends JpaRepository<AccessLogEntity, Long>, JpaSpecificationExecutor<AccessLogEntity> {
}