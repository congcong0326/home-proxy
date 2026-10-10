package org.congcong.controlmanager.service.mail.inspection;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "mail.inspection")
public class InspectionProperties {
    @NotBlank
    private String primaryWorkerId = "default";
    @NotBlank
    private String timeZone = ZoneId.systemDefault().getId();
    @Min(1) @Max(90)
    private int authBaselineDays = 30;
    @Min(1) @Max(30)
    private int authMinimumHistoryDays = 7;
    @Min(1)
    private int diskSampleMinutes = 10;
    @Min(1)
    private int hddTemperatureThreshold = 50;
    @Min(1)
    private int ssdTemperatureThreshold = 70;

    @AssertTrue(message = "认证历史最小天数必须不大于基线窗口，并且巡检时区必须有效")
    public boolean isInspectionConfigurationValid() {
        try { ZoneId.of(timeZone); }
        catch (RuntimeException error) { return false; }
        return authMinimumHistoryDays <= authBaselineDays;
    }
}
