package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.AdminTokenBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminTokenBlacklistRepository extends JpaRepository<AdminTokenBlacklist, String> {
}