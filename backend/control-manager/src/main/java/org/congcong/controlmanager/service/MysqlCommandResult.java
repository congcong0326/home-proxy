package org.congcong.controlmanager.service;

public record MysqlCommandResult(int exitCode, String stderr) {
}
