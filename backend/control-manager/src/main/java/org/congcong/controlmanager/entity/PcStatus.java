package org.congcong.controlmanager.entity;

import lombok.Data;

@Data
public class PcStatus {

    private String name;

    private String ip;

    private Boolean online;
    
    private Boolean enabled;
    
    private String macAddress;
    
    private Integer wolPort;
    
    private String notes;
}
