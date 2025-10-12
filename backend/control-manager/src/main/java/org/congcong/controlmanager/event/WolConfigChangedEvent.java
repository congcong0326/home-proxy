package org.congcong.controlmanager.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * WOL配置变更事件
 */
@Getter
public class WolConfigChangedEvent extends ApplicationEvent {
    
    public enum ChangeType {
        CREATED, UPDATED, DELETED, ENABLED, DISABLED
    }
    
    private final ChangeType changeType;
    private final Long configId;
    private final String configName;
    
    public WolConfigChangedEvent(Object source, ChangeType changeType, Long configId, String configName) {
        super(source);
        this.changeType = changeType;
        this.configId = configId;
        this.configName = configName;
    }

}