package org.congcong.controlmanager.dto;

import lombok.Data;

@Data
public class SetupStatusResponse {
    private boolean setupRequired;

    public SetupStatusResponse() {}

    public SetupStatusResponse(boolean setupRequired) {
        this.setupRequired = setupRequired;
    }
}
