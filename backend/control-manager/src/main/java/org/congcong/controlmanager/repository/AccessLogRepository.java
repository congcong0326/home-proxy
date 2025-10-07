package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.AccessLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessLogRepository extends JpaRepository<AccessLogEntity, Long> {
}