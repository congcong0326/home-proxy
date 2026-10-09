package org.congcong.proxyworker.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.congcong.common.enums.LogLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingLevelServiceTest {
    @ParameterizedTest
    @EnumSource(LogLevel.class)
    void filtersBothApplicationAndDependencyLogs(LogLevel configuredLevel) {
        LoggerContext context = new LoggerContext();
        try {
            LoggingLevelService service = new LoggingLevelService(context);
            service.apply(configuredLevel);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.setContext(context);
            appender.start();
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
            Level threshold = Level.valueOf(configuredLevel.name());
            for (String name : List.of("org.congcong.proxyworker.server.RelayHandler", "io.netty.channel.Channel")) {
                Logger logger = context.getLogger(name);
                for (Level eventLevel : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
                    boolean enabled = eventLevel.isGreaterOrEqual(threshold);
                    assertEquals(enabled, logger.isEnabledFor(eventLevel));
                    int before = appender.list.size();
                    logger.atLevel(org.slf4j.event.Level.valueOf(eventLevel.toString())).log("event");
                    assertEquals(before + (enabled ? 1 : 0), appender.list.size());
                }
            }
        } finally {
            context.stop();
        }
    }

    @Test
    void comparesActualLevelAndDefaultsMissingConfigurationToInfo() {
        LoggerContext context = new LoggerContext();
        try {
            LoggingLevelService service = new LoggingLevelService(context);
            assertTrue(service.apply(null));
            assertEquals(Level.INFO, context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel());
            assertFalse(service.apply(LogLevel.INFO));
            assertTrue(service.apply(LogLevel.WARN));
            assertFalse(service.apply(LogLevel.WARN));
            // Compare Logback itself, rather than a remembered value.
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.ERROR);
            assertTrue(service.apply(LogLevel.WARN));
        } finally {
            context.stop();
        }
    }
}
