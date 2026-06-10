package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.WorkerStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerStatusRepository extends JpaRepository<WorkerStatus, String> {
}
