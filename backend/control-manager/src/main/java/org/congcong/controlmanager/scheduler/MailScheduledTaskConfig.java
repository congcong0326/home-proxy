package org.congcong.controlmanager.scheduler;

import lombok.Data;

@Data
public class MailScheduledTaskConfig {
    /**
     * 要发送的 mail bizKey。
     */
    private String bizKey;

    /**
     * 可选的 requestId 前缀，用于幂等或溯源。
     */
    private String requestIdPrefix;
}
