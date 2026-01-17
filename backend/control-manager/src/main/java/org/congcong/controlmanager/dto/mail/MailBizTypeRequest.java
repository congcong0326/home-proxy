package org.congcong.controlmanager.dto.mail;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MailBizTypeRequest {

    @NotBlank
    private String bizKey;
}
