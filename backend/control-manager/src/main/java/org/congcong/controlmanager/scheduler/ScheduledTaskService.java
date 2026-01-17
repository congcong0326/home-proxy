package org.congcong.controlmanager.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.dto.scheduler.ScheduledTaskDTO;
import org.congcong.controlmanager.dto.scheduler.ScheduledTaskRequest;
import org.congcong.controlmanager.dto.scheduler.ScheduledTaskToggleRequest;
import org.congcong.controlmanager.entity.scheduler.ScheduledTask;
import org.congcong.controlmanager.repository.scheduler.ScheduledTaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduledTaskService {

    private final TaskScheduler taskScheduler;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final List<ScheduledTaskHandler> taskHandlers;
    private final ObjectMapper objectMapper;

    private final Map<Long, ScheduledFuture<?>> futureMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        scheduledTaskRepository.findByEnabledTrue().forEach(this::registerTask);
    }

    public List<ScheduledTaskDTO> listAll() {
        return scheduledTaskRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId() == null ? 0L : b.getId(), a.getId() == null ? 0L : a.getId()))
                .map(this::toDto)
                .toList();
    }

    public ScheduledTaskDTO create(ScheduledTaskRequest request) {
        validateCron(request.getCronExpression());
        if (scheduledTaskRepository.findByTaskKey(request.getTaskKey()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "taskKey 已存在");
        }
        ScheduledTask task = new ScheduledTask();
        fillTask(task, request);
        ScheduledTask saved = scheduledTaskRepository.save(task);
        if (Boolean.TRUE.equals(request.getEnabled())) {
            registerTask(saved);
        }
        return toDto(saved);
    }

    public ScheduledTaskDTO update(Long id, ScheduledTaskRequest request) {
        ScheduledTask task = scheduledTaskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        validateCron(request.getCronExpression());
        scheduledTaskRepository.findByTaskKey(request.getTaskKey())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "taskKey 已存在");
                });
        boolean enable = request.getEnabled() == null ? task.isEnabled() : request.getEnabled();
        fillTask(task, request);
        task.setEnabled(enable);
        ScheduledTask saved = scheduledTaskRepository.save(task);
        if (enable) {
            registerTask(saved);
        } else {
            cancelTask(saved.getId());
        }
        return toDto(saved);
    }

    public ScheduledTaskDTO toggle(Long id, ScheduledTaskToggleRequest request) {
        ScheduledTask task = scheduledTaskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        task.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        ScheduledTask saved = scheduledTaskRepository.save(task);
        if (saved.isEnabled()) {
            registerTask(saved);
        } else {
            cancelTask(saved.getId());
        }
        return toDto(saved);
    }

    public void delete(Long id) {
        cancelTask(id);
        scheduledTaskRepository.deleteById(id);
    }

    private void fillTask(ScheduledTask task, ScheduledTaskRequest request) {
        task.setTaskKey(request.getTaskKey());
        task.setTaskType(request.getTaskType());
        task.setBizKey(request.getBizKey());
        task.setCronExpression(request.getCronExpression());
        task.setDescription(request.getDescription());
        task.setEnabled(request.getEnabled() == null || request.getEnabled());
        task.setConfigJson(writeConfigJson(request.getConfig()));
    }

    private void registerTask(ScheduledTask task) {
        cancelTask(task.getId());
        ScheduledTaskHandler handler = resolveHandler(task.getTaskType());
        Runnable runnable = handler.buildTask(task);
        Runnable wrapped = () -> {
            try {
                runnable.run();
                task.setLastExecutedAt(LocalDateTime.now());
                scheduledTaskRepository.save(task);
            } catch (Exception e) {
                log.warn("执行任务失败 taskKey={} type={}", task.getTaskKey(), task.getTaskType(), e);
            }
        };
        CronTrigger trigger = new CronTrigger(task.getCronExpression(), ZoneId.systemDefault());
        ScheduledFuture<?> future = taskScheduler.schedule(wrapped, trigger);
        if (future != null) {
            futureMap.put(task.getId(), future);
            log.info("已注册定时任务 taskKey={} cron={}", task.getTaskKey(), task.getCronExpression());
        }
    }

    private void cancelTask(Long id) {
        if (id == null) return;
        ScheduledFuture<?> future = futureMap.remove(id);
        if (future != null) {
            future.cancel(false);
            log.info("已取消定时任务 id={}", id);
        }
    }

    private ScheduledTaskHandler resolveHandler(String taskType) {
        return taskHandlers.stream()
                .filter(h -> h.supports(taskType))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务类型: " + taskType));
    }

    private void validateCron(String cron) {
        if (!StringUtils.hasText(cron)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cronExpression 不能为空");
        }
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的 cron 表达式: " + cron);
        }
    }

    private ScheduledTaskDTO toDto(ScheduledTask task) {
        return new ScheduledTaskDTO(
                task.getId(),
                task.getTaskKey(),
                task.getTaskType(),
                task.getBizKey(),
                task.getCronExpression(),
                task.getDescription(),
                task.isEnabled(),
                task.getLastExecutedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                readConfigMap(task.getConfigJson())
        );
    }

    private Map<String, Object> readConfigMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析任务配置失败: {}", json, e);
            return null;
        }
    }

    private String writeConfigJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务配置序列化失败");
        }
    }
}
