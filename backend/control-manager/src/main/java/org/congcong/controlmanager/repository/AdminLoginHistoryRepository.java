package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.AdminLoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminLoginHistoryRepository extends JpaRepository<AdminLoginHistory, Long> {
}