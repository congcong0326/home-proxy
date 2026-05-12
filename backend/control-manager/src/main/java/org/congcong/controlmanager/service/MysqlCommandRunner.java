package org.congcong.controlmanager.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public interface MysqlCommandRunner {
    MysqlCommandResult run(List<String> command, Map<String, String> environment,
                           InputStream stdin, OutputStream stdout) throws Exception;
}
