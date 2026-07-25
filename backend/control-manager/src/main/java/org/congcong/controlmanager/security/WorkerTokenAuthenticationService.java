package org.congcong.controlmanager.security;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkerTokenAuthenticationService {
    private static final String TOKEN_PREFIX = "hpw_";
    private final ProxyWorkerRepository proxyWorkerRepository;

    public Optional<ProxyWorker> findWorker(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX) || token.length() <= TOKEN_PREFIX.length()) {
            return Optional.empty();
        }
        return proxyWorkerRepository.findByTokenHash(hash(token));
    }

    public Optional<WorkerPrincipal> authenticate(String token) {
        return findWorker(token).filter(worker -> Boolean.TRUE.equals(worker.getEnabled()))
                .map(worker -> new WorkerPrincipal(worker.getWorkerId(), worker.getDisplayName()));
    }

    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
