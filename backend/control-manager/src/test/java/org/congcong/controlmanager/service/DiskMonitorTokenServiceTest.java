package org.congcong.controlmanager.service;

import org.congcong.controlmanager.config.DiskMonitorProperties;
import org.congcong.controlmanager.entity.disk.DiskMonitorSettingEntity;
import org.congcong.controlmanager.repository.disk.DiskMonitorSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiskMonitorTokenService")
class DiskMonitorTokenServiceTest {

    @Mock
    private DiskMonitorSettingRepository settingRepository;

    @Test
    @DisplayName("没有持久化 Token 时随机生成并保存")
    void getOrCreatePushTokenGeneratesAndPersistsWhenMissing() {
        DiskMonitorProperties properties = new DiskMonitorProperties();
        DiskMonitorTokenService service = new DiskMonitorTokenService(
                settingRepository, properties, new IncrementingSecureRandom());
        when(settingRepository.findById(DiskMonitorTokenService.PUSH_TOKEN_KEY)).thenReturn(Optional.empty());
        when(settingRepository.save(any(DiskMonitorSettingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String token = service.getOrCreatePushToken().token();

        assertFalse(token.isBlank());
        assertEquals(43, token.length());
        ArgumentCaptor<DiskMonitorSettingEntity> captor = ArgumentCaptor.forClass(DiskMonitorSettingEntity.class);
        verify(settingRepository).save(captor.capture());
        assertEquals(DiskMonitorTokenService.PUSH_TOKEN_KEY, captor.getValue().getSettingKey());
        assertEquals(token, captor.getValue().getSettingValue());
    }

    @Test
    @DisplayName("已有持久化 Token 时原样明文返回")
    void getOrCreatePushTokenReturnsPersistedPlainToken() {
        DiskMonitorTokenService service = new DiskMonitorTokenService(
                settingRepository, new DiskMonitorProperties(), new IncrementingSecureRandom());
        when(settingRepository.findById(DiskMonitorTokenService.PUSH_TOKEN_KEY))
                .thenReturn(Optional.of(setting("persisted-visible-token")));

        assertEquals("persisted-visible-token", service.getOrCreatePushToken().token());
    }

    @Test
    @DisplayName("重置 Token 时替换为新的随机值")
    void regeneratePushTokenReplacesPersistedValue() {
        DiskMonitorTokenService service = new DiskMonitorTokenService(
                settingRepository, new DiskMonitorProperties(), new IncrementingSecureRandom());
        when(settingRepository.findById(DiskMonitorTokenService.PUSH_TOKEN_KEY))
                .thenReturn(Optional.of(setting("old-token")));
        when(settingRepository.save(any(DiskMonitorSettingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String nextToken = service.regeneratePushToken().token();

        assertNotEquals("old-token", nextToken);
        ArgumentCaptor<DiskMonitorSettingEntity> captor = ArgumentCaptor.forClass(DiskMonitorSettingEntity.class);
        verify(settingRepository).save(captor.capture());
        assertEquals(nextToken, captor.getValue().getSettingValue());
    }

    @Test
    @DisplayName("推送校验使用持久化 Token")
    void isValidPushTokenUsesPersistedToken() {
        DiskMonitorTokenService service = new DiskMonitorTokenService(
                settingRepository, new DiskMonitorProperties(), new IncrementingSecureRandom());
        when(settingRepository.findById(DiskMonitorTokenService.PUSH_TOKEN_KEY))
                .thenReturn(Optional.of(setting("secret-token")));

        assertTrue(service.isValidPushToken("secret-token"));
        assertFalse(service.isValidPushToken("wrong-token"));
        assertFalse(service.isValidPushToken(null));
    }

    private DiskMonitorSettingEntity setting(String value) {
        DiskMonitorSettingEntity entity = new DiskMonitorSettingEntity();
        entity.setSettingKey(DiskMonitorTokenService.PUSH_TOKEN_KEY);
        entity.setSettingValue(value);
        return entity;
    }

    private static class IncrementingSecureRandom extends SecureRandom {
        private int next = 1;

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) next);
            next++;
        }
    }
}
