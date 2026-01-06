package org.congcong.controlmanager.dto.mail;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MailGatewayRequest {
    @NotBlank
    private String name;

    @NotBlank
    private String host;

    @NotNull
    @Min(1)
    private Integer port;

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    private String protocol = "smtp";
    private boolean sslEnabled;
    private boolean starttlsEnabled;
    private String fromAddress;
    private boolean enabled = true;
}
