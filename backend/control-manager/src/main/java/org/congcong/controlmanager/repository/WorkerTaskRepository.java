package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.WorkerTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkerTaskRepository extends JpaRepository<WorkerTask, Long> {
    List<WorkerTask> findByConsumedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
}
