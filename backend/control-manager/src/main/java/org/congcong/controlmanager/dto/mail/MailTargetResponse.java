package org.congcong.controlmanager.dto.mail;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class MailTargetResponse {
    private Long id;
    private String bizKey;
    private String toList;
    private String ccList;
    private String bccList;
    private Long gatewayId;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
