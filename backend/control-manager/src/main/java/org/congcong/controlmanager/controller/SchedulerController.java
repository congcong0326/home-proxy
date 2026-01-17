package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.scheduler.ScheduledTaskDTO;
import org.congcong.controlmanager.dto.scheduler.ScheduledTaskRequest;
import org.congcong.controlmanager.dto.scheduler.ScheduledTaskToggleRequest;
import org.congcong.controlmanager.scheduler.ScheduledTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/scheduler")
@Validated
@RequiredArgsConstructor
public class SchedulerController {

    private final ScheduledTaskService scheduledTaskService;

    @GetMapping("/tasks")
    public ResponseEntity<List<ScheduledTaskDTO>> list() {
        return ResponseEntity.ok(scheduledTaskService.listAll());
    }

    @PostMapping("/tasks")
    public ResponseEntity<ScheduledTaskDTO> create(@Valid @RequestBody ScheduledTaskRequest request) {
        return ResponseEntity.ok(scheduledTaskService.create(request));
    }

    @PutMapping("/tasks/{id}")
    public ResponseEntity<ScheduledTaskDTO> update(@PathVariable Long id, @Valid @RequestBody ScheduledTaskRequest request) {
        return ResponseEntity.ok(scheduledTaskService.update(id, request));
    }

    @PostMapping("/tasks/{id}/toggle")
    public ResponseEntity<ScheduledTaskDTO> toggle(@PathVariable Long id, @Valid @RequestBody ScheduledTaskToggleRequest request) {
        return ResponseEntity.ok(scheduledTaskService.toggle(id, request));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scheduledTaskService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
