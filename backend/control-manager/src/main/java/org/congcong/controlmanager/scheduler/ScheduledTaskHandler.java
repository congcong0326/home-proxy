package org.congcong.controlmanager.scheduler;

import org.congcong.controlmanager.entity.scheduler.ScheduledTask;

public interface ScheduledTaskHandler {
    /**
     * 是否支持该任务类型。
     */
    boolean supports(String taskType);

    /**
     * 根据任务定义构造 runnable。
     */
    Runnable buildTask(ScheduledTask task);
}
