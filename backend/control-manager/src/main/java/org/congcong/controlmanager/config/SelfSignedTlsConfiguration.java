package org.congcong.controlmanager.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Configures HTTPS without requiring a keystore to be provisioned on the host. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Slf4j
public class SelfSignedTlsConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> selfSignedTlsCustomizer() {
        SelfSignedTlsKeyStore.GeneratedKeyStore keyStore = SelfSignedTlsKeyStore.generate();
        log.warn("控制端正在使用启动时生成的自签名 HTTPS 证书；worker 不校验证书，浏览器会显示证书警告");

        return factory -> {
            Ssl ssl = new Ssl();
            ssl.setEnabled(true);
            ssl.setKeyStore(keyStore.path().toUri().toString());
            ssl.setKeyStoreType("PKCS12");
            ssl.setKeyStorePassword(keyStore.password());
            ssl.setKeyAlias(SelfSignedTlsKeyStore.KEY_ALIAS);
            factory.setSsl(ssl);
        };
    }
}
