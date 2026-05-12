package org.congcong.controlmanager.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.dto.ruleset.RuleSetBatchSyncRequest;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncResultDTO;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncStatus;
import org.congcong.controlmanager.entity.scheduler.ScheduledTask;
import org.congcong.controlmanager.service.RuleSetService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RuleSetSyncScheduledTaskHandler implements ScheduledTaskHandler {

    public static final String TASK_TYPE = "rule_set_sync";

    private final RuleSetService ruleSetService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String taskType) {
        return TASK_TYPE.equalsIgnoreCase(taskType);
    }

    @Override
    public Runnable buildTask(ScheduledTask task) {
        RuleSetSyncTaskConfig config = parseConfig(task);
        return () -> {
            RuleSetBatchSyncRequest request = new RuleSetBatchSyncRequest();
            request.setRuleSetIds(config.getRuleSetIds());
            request.setEnabledOnly(config.getEnabledOnly());
            request.setPublishedOnly(config.getPublishedOnly());
            List<RuleSetSyncResultDTO> results = ruleSetService.syncRuleSets(request);
            long updatedCount = results.stream().filter(result -> result.getStatus() == RuleSetSyncStatus.UPDATED).count();
            long failedCount = results.stream().filter(result -> result.getStatus() == RuleSetSyncStatus.FAILED).count();
            log.info("定时任务同步规则集完成 taskKey={} total={} updated={} failed={}",
                    task.getTaskKey(), results.size(), updatedCount, failedCount);
        };
    }

    private RuleSetSyncTaskConfig parseConfig(ScheduledTask task) {
        if (!StringUtils.hasText(task.getConfigJson())) {
            return new RuleSetSyncTaskConfig();
        }
        try {
            return objectMapper.readValue(task.getConfigJson(), RuleSetSyncTaskConfig.class);
        } catch (Exception e) {
            log.warn("解析规则集同步任务配置失败，使用默认配置 taskKey={} config={}", task.getTaskKey(), task.getConfigJson(), e);
            return new RuleSetSyncTaskConfig();
        }
    }
}
