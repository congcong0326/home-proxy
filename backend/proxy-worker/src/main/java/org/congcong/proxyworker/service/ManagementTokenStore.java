package org.congcong.proxyworker.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/** Persists the token accepted by the worker's controller-facing WSS endpoint. */
public final class ManagementTokenStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_PREFIX = "hpw_";
    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
            PosixFilePermissions.fromString("rw-------");

    private ManagementTokenStore() {
    }

    public static String loadOrCreate(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("worker.management.tokenFile 未配置");
        }
        Path tokenPath = Path.of(configuredPath);
        try {
            if (Files.isRegularFile(tokenPath)) {
                return validate(Files.readString(tokenPath, StandardCharsets.UTF_8).trim());
            }
            Path parent = tokenPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String token = generate();
            try {
                Files.writeString(tokenPath, token + System.lineSeparator(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                restrictPermissions(tokenPath);
                return token;
            } catch (FileAlreadyExistsException ignored) {
                return validate(Files.readString(tokenPath, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取或创建 worker 管理 Token 文件: " + tokenPath, e);
        }
    }

    private static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String validate(String token) {
        if (!token.startsWith(TOKEN_PREFIX) || token.length() <= TOKEN_PREFIX.length()) {
            throw new IllegalStateException("worker 管理 Token 格式无效");
        }
        return token;
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ignored) {
            // Docker volumes on Linux support POSIX permissions. Other filesystems retain their defaults.
        } catch (IOException e) {
            throw new IllegalStateException("无法设置 worker 管理 Token 文件权限", e);
        }
    }
}
