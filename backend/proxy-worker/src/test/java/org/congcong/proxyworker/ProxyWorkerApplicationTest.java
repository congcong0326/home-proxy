package org.congcong.proxyworker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.proxyworker.service.AggregateConfigService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyWorkerApplicationTest {

    @Test
    void configChangeLogContainsSummaryButNotFullConfigData() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ProxyWorkerApplication.class);
        Level originalLevel = logger.getLevel();
        boolean originalAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();

        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            AggregateConfigService.ConfigChangeListener listener = newConfigChangeListener();
            listener.onConfigChanged(aggregateConfig());

            String messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));

            assertTrue(messages.contains("入站配置数量: 0"));
            assertTrue(messages.contains("配置哈希: hash-001"));
            assertFalse(messages.contains("AggregateConfigResponse("));
            assertFalse(messages.contains("配置数据:"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            logger.setAdditive(originalAdditive);
            appender.stop();
        }
    }

    private static AggregateConfigService.ConfigChangeListener newConfigChangeListener() throws Exception {
        Class<?> listenerClass = Class.forName("org.congcong.proxyworker.ProxyWorkerApplication$ConfigChangeListener");
        Constructor<?> constructor = listenerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (AggregateConfigService.ConfigChangeListener) constructor.newInstance();
    }

    private static AggregateConfigResponse aggregateConfig() {
        AggregateConfigResponse response = new AggregateConfigResponse();
        response.setVersion("v-test");
        response.setGeneratedAt(LocalDateTime.of(2026, 6, 28, 12, 0));
        response.setInbounds(List.of());
        response.setRoutes(List.of());
        response.setRateLimits(List.of());
        response.setUsers(List.of());
        response.setRuleSets(List.of());
        response.setConfigHash("hash-001");
        return response;
    }
}
