package org.congcong.proxyworker.integration.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class XrayProcess implements AutoCloseable {
    private final Process process;
    private final Path workDir;
    private final Path stdout;
    private final Path stderr;

    private XrayProcess(Process process, Path workDir, Path stdout, Path stderr) {
        this.process = process;
        this.workDir = workDir;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public static XrayProcess start(String name, String configJson, int... tcpPorts) {
        try {
            Path binary = XrayBinaryManager.resolve();
            Path workDir = workRoot().resolve(name + "-" + System.nanoTime());
            Files.createDirectories(workDir);
            Path config = workDir.resolve("config.json");
            Path stdout = workDir.resolve("stdout.log");
            Path stderr = workDir.resolve("stderr.log");
            Files.writeString(config, configJson, StandardCharsets.UTF_8);

            Process process = new ProcessBuilder(binary.toString(), "run", "-config", config.toString())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            XrayProcess xray = new XrayProcess(process, workDir, stdout, stderr);
            for (int port : tcpPorts) {
                xray.waitForTcpPort(port);
            }
            return xray;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start xray " + name, e);
        }
    }

    private void waitForTcpPort(int port) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("xray exited before port " + port + " became ready\n" + logs());
            }
            try {
                NetworkWait.waitForTcpPort("127.0.0.1", port, Duration.ofMillis(300));
                return;
            } catch (IllegalStateException ignored) {
            }
        }
        throw new IllegalStateException("Timed out waiting for xray port " + port + "\n" + logs());
    }

    public Path workDir() {
        return workDir;
    }

    private static Path workRoot() {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if ("proxy-worker".equals(userDir.getFileName().toString())) {
            return userDir.resolve("target/it-xray");
        }
        return userDir.resolve("backend/proxy-worker/target/it-xray");
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private String logs() {
        return Arrays.asList(stdout, stderr).stream()
                .map(this::tail)
                .collect(Collectors.joining("\n"));
    }

    private String tail(Path path) {
        try {
            if (!Files.exists(path)) {
                return path + " does not exist";
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int start = Math.max(0, content.length() - 4000);
            return "== " + path + " ==\n" + content.substring(start);
        } catch (IOException e) {
            return "Failed to read " + path + ": " + e.getMessage();
        }
    }
}
