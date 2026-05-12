package org.congcong.controlmanager.dto.disk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
public class DiskPushRequest {

    @NotBlank(message = "主机ID不能为空")
    private String hostId;

    private String hostName;

    private Instant sampledAt;

    @Valid
    @NotEmpty(message = "磁盘采样不能为空")
    private List<DiskSample> disks;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiskSample {

        @NotBlank(message = "设备名不能为空")
        private String device;

        @NotBlank(message = "smartctl输出不能为空")
        private String smartctlOutput;
    }
}
