package org.congcong.controlmanager.dto.mail;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MailTargetRequest {
    @NotBlank
    private String bizKey;

    @NotBlank
    private String toList;

    private String ccList;
    private String bccList;
    private Long gatewayId;
    private boolean enabled = true;
}
