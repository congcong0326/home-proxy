package org.congcong.controlmanager.service;

import org.congcong.controlmanager.config.DiskMonitorProperties;
import org.congcong.controlmanager.dto.disk.DiskPushTokenResponse;
import org.congcong.controlmanager.entity.disk.DiskMonitorSettingEntity;
import org.congcong.controlmanager.repository.disk.DiskMonitorSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
public class DiskMonitorTokenService {

    public static final String PUSH_TOKEN_KEY = "disk_push_token";

    private static final int TOKEN_BYTES = 32;

    private final DiskMonitorSettingRepository settingRepository;
    private final DiskMonitorProperties properties;
    private final SecureRandom secureRandom;

    @Autowired
    public DiskMonitorTokenService(DiskMonitorSettingRepository settingRepository,
                                   DiskMonitorProperties properties) {
        this(settingRepository, properties, new SecureRandom());
    }

    public DiskMonitorTokenService(DiskMonitorSettingRepository settingRepository,
                                   DiskMonitorProperties properties,
                                   SecureRandom secureRandom) {
        this.settingRepository = settingRepository;
        this.properties = properties;
        this.secureRandom = secureRandom;
    }

    @Transactional
    public DiskPushTokenResponse getOrCreatePushToken() {
        String token = findPersistedPushToken()
                .orElseGet(() -> saveToken(configuredPushToken().orElseGet(this::generateToken)));
        return new DiskPushTokenResponse(token);
    }

    @Transactional
    public DiskPushTokenResponse regeneratePushToken() {
        return new DiskPushTokenResponse(saveToken(generateToken()));
    }

    @Transactional(readOnly = true)
    public boolean isValidPushToken(String submittedToken) {
        Optional<String> expected = findPersistedPushToken().or(this::configuredPushToken);
        if (expected.isEmpty() || submittedToken == null || submittedToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.get().getBytes(StandardCharsets.UTF_8),
                submittedToken.getBytes(StandardCharsets.UTF_8));
    }

    private Optional<String> findPersistedPushToken() {
        return settingRepository.findById(PUSH_TOKEN_KEY)
                .map(DiskMonitorSettingEntity::getSettingValue)
                .map(String::trim)
                .filter(token -> !token.isBlank());
    }

    private Optional<String> configuredPushToken() {
        String token = properties.getPushToken();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(token.trim());
    }

    private String saveToken(String token) {
        DiskMonitorSettingEntity setting = settingRepository.findById(PUSH_TOKEN_KEY)
                .orElseGet(DiskMonitorSettingEntity::new);
        setting.setSettingKey(PUSH_TOKEN_KEY);
        setting.setSettingValue(token);
        return settingRepository.save(setting).getSettingValue();
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
