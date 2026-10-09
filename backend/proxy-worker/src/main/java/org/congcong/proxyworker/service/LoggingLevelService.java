package org.congcong.proxyworker.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.congcong.common.enums.LogLevel;
import org.slf4j.LoggerFactory;

/** Changes the worker's logging threshold without rebuilding appenders or listeners. */
public class LoggingLevelService {
    private final Logger rootLogger;

    public LoggingLevelService() {
        this((LoggerContext) LoggerFactory.getILoggerFactory());
    }

    public LoggingLevelService(LoggerContext context) {
        this.rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    /** @return whether the current level actually changed */
    public synchronized boolean apply(LogLevel configuredLevel) {
        LogLevel logLevel = configuredLevel == null ? LogLevel.INFO : configuredLevel;
        Level desiredLevel = Level.valueOf(logLevel.name());
        if (desiredLevel.equals(rootLogger.getLevel())) {
            return false;
        }
        rootLogger.setLevel(desiredLevel);
        return true;
    }
}
