package org.congcong.controlmanager.dto.mail;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.congcong.controlmanager.enums.MailSendStatus;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class MailSendLogDTO {
    private Long id;
    private String bizKey;
    private Long gatewayId;
    private String toList;
    private String ccList;
    private String bccList;
    private String subject;
    private String contentType;
    private Integer contentSize;
    private MailSendStatus status;
    private String errorMessage;
    private String requestId;
    private LocalDateTime createdAt;
}
