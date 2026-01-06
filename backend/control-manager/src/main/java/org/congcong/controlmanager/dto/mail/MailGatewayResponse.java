package org.congcong.controlmanager.dto.mail;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class MailGatewayResponse {
    private Long id;
    private String name;
    private String host;
    private Integer port;
    private String username;
    private String protocol;
    private boolean sslEnabled;
    private boolean starttlsEnabled;
    private String fromAddress;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
