package org.congcong.proxyworker.service;

import org.congcong.common.dto.WorkerTaskDTO;
import org.congcong.common.dto.WorkerTaskResultDTO;

public interface WorkerTaskExecutor {
    WorkerTaskResultDTO execute(WorkerTaskDTO task);
}
