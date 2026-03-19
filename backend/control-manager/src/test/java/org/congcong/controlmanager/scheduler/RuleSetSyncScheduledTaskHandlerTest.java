package org.congcong.controlmanager.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.controlmanager.dto.ruleset.RuleSetBatchSyncRequest;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncResultDTO;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncStatus;
import org.congcong.controlmanager.entity.scheduler.ScheduledTask;
import org.congcong.controlmanager.service.RuleSetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RuleSetSyncScheduledTaskHandler 单元测试")
class RuleSetSyncScheduledTaskHandlerTest {

    @Mock
    private RuleSetService ruleSetService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RuleSetSyncScheduledTaskHandler handler;

    @Test
    @DisplayName("支持 rule_set_sync 任务类型")
    void testSupportsRuleSetSyncTaskType() {
        assertTrue(handler.supports("rule_set_sync"));
        assertTrue(handler.supports("RULE_SET_SYNC"));
        assertFalse(handler.supports("mail_biz"));
    }

    @Test
    @DisplayName("执行任务时透传批量同步配置")
    void testBuildTaskRunPassesSyncRequest() throws Exception {
        ScheduledTask task = new ScheduledTask();
        task.setTaskKey("sync-ai");
        task.setConfigJson("{\"ruleSetIds\":[1,2],\"enabledOnly\":false,\"publishedOnly\":true}");

        RuleSetSyncTaskConfig config = new RuleSetSyncTaskConfig();
        config.setRuleSetIds(List.of(1L, 2L));
        config.setEnabledOnly(false);
        config.setPublishedOnly(true);

        RuleSetSyncResultDTO result = new RuleSetSyncResultDTO();
        result.setStatus(RuleSetSyncStatus.UPDATED);

        when(objectMapper.readValue(task.getConfigJson(), RuleSetSyncTaskConfig.class)).thenReturn(config);
        when(ruleSetService.syncRuleSets(any(RuleSetBatchSyncRequest.class))).thenReturn(List.of(result));

        handler.buildTask(task).run();

        ArgumentCaptor<RuleSetBatchSyncRequest> captor = ArgumentCaptor.forClass(RuleSetBatchSyncRequest.class);
        verify(ruleSetService).syncRuleSets(captor.capture());
        assertEquals(List.of(1L, 2L), captor.getValue().getRuleSetIds());
        assertFalse(captor.getValue().getEnabledOnly());
        assertTrue(captor.getValue().getPublishedOnly());
    }

    @Test
    @DisplayName("配置解析失败时使用默认同步配置")
    void testBuildTaskRunFallbackToDefaultConfig() throws Exception {
        ScheduledTask task = new ScheduledTask();
        task.setTaskKey("sync-ai");
        task.setConfigJson("{bad-json}");

        RuleSetSyncResultDTO result = new RuleSetSyncResultDTO();
        result.setStatus(RuleSetSyncStatus.UNCHANGED);

        when(objectMapper.readValue(task.getConfigJson(), RuleSetSyncTaskConfig.class))
                .thenThrow(new RuntimeException("bad json"));
        when(ruleSetService.syncRuleSets(any(RuleSetBatchSyncRequest.class))).thenReturn(List.of(result));

        handler.buildTask(task).run();

        ArgumentCaptor<RuleSetBatchSyncRequest> captor = ArgumentCaptor.forClass(RuleSetBatchSyncRequest.class);
        verify(ruleSetService).syncRuleSets(captor.capture());
        assertNull(captor.getValue().getRuleSetIds());
        assertTrue(captor.getValue().getEnabledOnly());
        assertFalse(captor.getValue().getPublishedOnly());
    }
}
