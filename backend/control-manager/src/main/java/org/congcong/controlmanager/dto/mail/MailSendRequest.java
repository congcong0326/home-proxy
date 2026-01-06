package org.congcong.controlmanager.dto.mail;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MailSendRequest {
    @NotBlank
    private String bizKey;

    @NotBlank
    private String subject;

    @NotBlank
    private String content;

    private String contentType = "text/plain";
    private String requestId;
}
