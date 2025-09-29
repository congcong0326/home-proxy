package org.congcong.controlmanager.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "admin.auth")
public class AdminAuthProperties {
    private Jwt jwt = new Jwt();
    private Cookie cookie = new Cookie();
    private Login login = new Login();

    @Setter
    @Getter
    public static class Jwt {
        @NotBlank
        private String secret;
        @NotNull @Min(1)
        private Integer ttlDays = 30;
        @NotBlank
        private String issuer = "nas-proxy-management";

    }

    @Setter
    @Getter
    public static class Cookie {
        private boolean enabled = false;
        private String name = "Admin-Session";
        private String domain;
        private boolean secure = true;

    }

    @Setter
    @Getter
    public static class Login {
        @NotNull @Min(1)
        private Integer rateLimitPerMinute = 5;

    }
}