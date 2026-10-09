package org.congcong.proxyworker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.common.dto.InboundConfigDTO;
import org.congcong.common.enums.LogLevel;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.service.AggregateConfigService;
import org.congcong.proxyworker.service.LoggingLevelService;
import org.congcong.proxyworker.server.ConfigApplyResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyWorkerApplicationTest {

    @Test
    void loggingOnlyChangeSkipsProxyRefreshButProxyChangesStillApply() {
        LoggerContext context = new LoggerContext();
        AtomicInteger refreshCount = new AtomicInteger();
        try {
            var listener = new ProxyWorkerApplication.ConfigChangeListener(new LoggingLevelService(context), configs -> {
                refreshCount.incrementAndGet();
                return ConfigApplyResult.applied();
            });
            AggregateConfigResponse config = aggregateConfig();
            InboundConfigDTO dns = new InboundConfigDTO();
            dns.setId(1L);
            dns.setProtocol(ProtocolType.DNS_SERVER);
            dns.setFakeIpEnabled(true);
            config.setInbounds(List.of(dns));
            assertTrue(listener.onConfigChanged(config).success());
            assertEquals(1, refreshCount.get());

            config.setLogLevel(LogLevel.WARN);
            config.setConfigHash("logging-only-hash");
            config.setGeneratedAt(LocalDateTime.now());
            assertTrue(listener.onConfigChanged(config).success());
            assertEquals(Level.WARN, context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel());
            assertEquals(1, refreshCount.get());
            assertTrue(listener.onConfigChanged(config).success());
            assertEquals(1, refreshCount.get());

            dns.setPort(5353);
            assertTrue(listener.onConfigChanged(config).success());
            assertEquals(2, refreshCount.get());
        } finally {
            context.stop();
        }
    }

    @Test
    void failedProxyApplyIsRetriedEvenWhenLoggingLevelIsUnchanged() {
        LoggerContext context = new LoggerContext();
        AtomicInteger attempts = new AtomicInteger();
        try {
            var listener = new ProxyWorkerApplication.ConfigChangeListener(new LoggingLevelService(context), configs ->
                    attempts.incrementAndGet() == 1
                            ? ConfigApplyResult.failure(null, new IllegalStateException("bind failed"))
                            : ConfigApplyResult.applied());
            AggregateConfigResponse config = aggregateConfig();
            assertFalse(listener.onConfigChanged(config).success());
            assertTrue(listener.onConfigChanged(config).success());
            assertEquals(2, attempts.get());
        } finally {
            context.stop();
        }
    }

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
        response.setWorkerId("default");
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
