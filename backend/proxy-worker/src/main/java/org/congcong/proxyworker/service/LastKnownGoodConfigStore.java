package org.congcong.proxyworker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.dto.AggregateConfigResponse;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/** Stores only configurations that have successfully started all affected inbounds. */
public class LastKnownGoodConfigStore {
    private final Path path;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public LastKnownGoodConfigStore(String fileName) {
        this.path = Path.of(fileName);
    }

    public Optional<AggregateConfigResponse> read() {
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(Files.readAllBytes(path), AggregateConfigResponse.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void write(AggregateConfigResponse config) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, objectMapper.writeValueAsBytes(config), StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            setOwnerOnlyPermissions(path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void setOwnerOnlyPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Docker Linux volumes support this; unsupported filesystems still retain their existing ACLs.
        }
    }
}
