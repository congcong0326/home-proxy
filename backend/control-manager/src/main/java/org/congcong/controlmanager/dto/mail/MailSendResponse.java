package org.congcong.controlmanager.dto.mail;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.congcong.controlmanager.enums.MailSendStatus;

@Data
@AllArgsConstructor
public class MailSendResponse {
    private Long sendLogId;
    private MailSendStatus status;
    private String errorMessage;
}
