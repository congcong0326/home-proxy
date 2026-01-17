package org.congcong.controlmanager.repository.scheduler;

import org.congcong.controlmanager.entity.scheduler.ScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {
    List<ScheduledTask> findByEnabledTrue();
    Optional<ScheduledTask> findByTaskKey(String taskKey);
}
