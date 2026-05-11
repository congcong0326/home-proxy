package org.congcong.proxyworker.integration.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class XrayBinaryManager {
    public static final String VERSION = System.getProperty("xray.version", "v26.3.27");

    private XrayBinaryManager() {
    }

    public static Path resolve() {
        String explicit = System.getenv("XRAY_BIN");
        if (explicit != null && !explicit.isBlank()) {
            Path binary = Paths.get(explicit);
            if (!Files.isExecutable(binary)) {
                throw new IllegalStateException("XRAY_BIN is not executable: " + binary);
            }
            return binary;
        }

        String asset = assetName();
        Path installDir = cacheRoot().resolve(VERSION).resolve(asset.substring(0, asset.length() - ".zip".length()));
        Path binary = installDir.resolve(isWindows() ? "xray.exe" : "xray");
        if (Files.isExecutable(binary)) {
            return binary;
        }

        try {
            Files.createDirectories(installDir);
            Path zip = installDir.resolve(asset);
            download(asset, zip);
            unzip(zip, installDir);
            if (!isWindows()) {
                binary.toFile().setExecutable(true);
            }
            validate(binary);
            return binary;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to prepare xray " + VERSION + " from " + asset, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare xray " + VERSION + " from " + asset, e);
        }
    }

    private static Path cacheRoot() {
        String configured = System.getProperty("xray.cache.dir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if ("proxy-worker".equals(userDir.getFileName().toString())
                && userDir.getParent() != null
                && "backend".equals(userDir.getParent().getFileName().toString())
                && userDir.getParent().getParent() != null) {
            return userDir.getParent().getParent().resolve(".it-cache/xray").normalize();
        }
        return userDir.resolve(".it-cache/xray").normalize();
    }

    private static void download(String asset, Path zip) throws IOException, InterruptedException {
        URI uri = URI.create("https://github.com/XTLS/Xray-core/releases/download/" + VERSION + "/" + asset);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "home-proxy-integration-tests")
                .build();
        HttpResponse<Path> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofFile(zip));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("xray download failed with HTTP " + response.statusCode() + ": " + uri);
        }
    }

    private static void unzip(Path zip, Path targetDir) throws IOException {
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path output = targetDir.resolve(entry.getName()).normalize();
                if (!output.startsWith(targetDir)) {
                    throw new IOException("Refusing zip entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zis, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void validate(Path binary) throws IOException, InterruptedException {
        if (!Files.isExecutable(binary)) {
            throw new IOException("xray binary not executable after extraction: " + binary);
        }
        Process process = new ProcessBuilder(binary.toString(), "version")
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("xray version command timed out: " + binary);
        }
        if (process.exitValue() != 0) {
            throw new IOException("xray version command failed with exit " + process.exitValue());
        }
    }

    private static String assetName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String normalizedArch = arch.contains("aarch64") || arch.contains("arm64") ? "arm64-v8a" : "64";
        if (os.contains("linux")) {
            return "Xray-linux-" + normalizedArch + ".zip";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "Xray-macos-" + normalizedArch + ".zip";
        }
        if (os.contains("windows")) {
            return "Xray-windows-" + normalizedArch + ".zip";
        }
        throw new IllegalStateException("Unsupported OS for xray integration tests: " + os + " " + arch);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
