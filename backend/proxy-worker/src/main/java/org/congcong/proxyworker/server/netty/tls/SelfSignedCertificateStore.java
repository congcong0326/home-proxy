package org.congcong.proxyworker.server.netty.tls;

import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateException;

/**
 * 入站 TLS 自签证书持久化：首次使用时生成，之后重启复用同一张证书。
 * 客户端跳过证书校验，证书只需稳定存在，不需要外部签发。
 */
final class SelfSignedCertificateStore {
    static final String CERT_FILE_NAME = "tls.crt";
    static final String KEY_FILE_NAME = "tls.key";
    private static final String SUBJECT = "home-proxy-worker";

    private SelfSignedCertificateStore() {
    }

    record CertificateFiles(Path certFile, Path keyFile, boolean created) {
    }

    static synchronized CertificateFiles loadOrCreate(Path directory) {
        Path certFile = directory.resolve(CERT_FILE_NAME);
        Path keyFile = directory.resolve(KEY_FILE_NAME);
        if (Files.isRegularFile(certFile) && Files.isRegularFile(keyFile)) {
            return new CertificateFiles(certFile, keyFile, false);
        }

        SelfSignedCertificate generated = null;
        try {
            Files.createDirectories(directory);
            generated = new SelfSignedCertificate(SUBJECT);
            // 先落私钥再落证书：证书存在即代表一对完整文件。
            install(generated.privateKey().toPath(), keyFile);
            install(generated.certificate().toPath(), certFile);
            return new CertificateFiles(certFile, keyFile, true);
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException("无法生成或保存入站 TLS 自签证书: " + directory, e);
        } finally {
            if (generated != null) {
                generated.delete();
            }
        }
    }

    private static void install(Path source, Path target) throws IOException {
        // createTempFile 在 POSIX 上为 rw-------，私钥落盘前不会被其他用户读到。
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, Files.readAllBytes(source));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
