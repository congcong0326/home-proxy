package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.AuthLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthLogRepository extends JpaRepository<AuthLogEntity, Long> {
}