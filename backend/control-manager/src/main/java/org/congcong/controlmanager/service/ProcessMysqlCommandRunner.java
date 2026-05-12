package org.congcong.controlmanager.service;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ProcessMysqlCommandRunner implements MysqlCommandRunner {

    @Override
    public MysqlCommandResult run(List<String> command, Map<String, String> environment,
                                  InputStream stdin, OutputStream stdout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        Process process = builder.start();

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicReference<Exception> streamError = new AtomicReference<>();

        Thread stdoutThread = new Thread(() -> copy(process.getInputStream(), stdout, streamError), "mysql-command-stdout");
        Thread stderrThread = new Thread(() -> copy(process.getErrorStream(), stderr, streamError), "mysql-command-stderr");
        Thread stdinThread = new Thread(() -> writeStdin(process, stdin, streamError), "mysql-command-stdin");

        stdoutThread.start();
        stderrThread.start();
        stdinThread.start();

        int exitCode = process.waitFor();
        stdoutThread.join();
        stderrThread.join();
        stdinThread.join();

        Exception error = streamError.get();
        if (error != null) {
            throw error;
        }

        return new MysqlCommandResult(exitCode, stderr.toString(StandardCharsets.UTF_8));
    }

    private static void writeStdin(Process process, InputStream stdin, AtomicReference<Exception> streamError) {
        try (OutputStream processInput = process.getOutputStream()) {
            if (stdin != null) {
                stdin.transferTo(processInput);
            }
        } catch (Exception e) {
            streamError.compareAndSet(null, e);
        }
    }

    private static void copy(InputStream input, OutputStream output, AtomicReference<Exception> streamError) {
        try (input) {
            input.transferTo(output);
        } catch (Exception e) {
            streamError.compareAndSet(null, e);
        }
    }
}
