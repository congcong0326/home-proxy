package org.congcong.controlmanager.controller;

import org.congcong.controlmanager.dto.disk.DiskPushRequest;
import org.congcong.controlmanager.dto.disk.DiskPushTokenResponse;
import org.congcong.controlmanager.service.DiskService;
import org.congcong.controlmanager.service.DiskMonitorTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiskMonitorController 主动推送接口")
class DiskMonitorControllerTest {

    @Mock
    private DiskService diskService;

    @Mock
    private DiskMonitorTokenService tokenService;

    private DiskMonitorController controller;

    @BeforeEach
    void setUp() {
        controller = new DiskMonitorController(diskService, tokenService);
    }

    @Test
    @DisplayName("推送接口拒绝缺失或错误的 Push Token")
    void pushRejectsInvalidToken() {
        DiskPushRequest request = request();
        when(tokenService.isValidPushToken("wrong-token")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.pushDisks("wrong-token", request, "10.0.0.2"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(diskService, never()).acceptPush(request, "10.0.0.2");
    }

    @Test
    @DisplayName("推送接口接受正确 Push Token 并调用服务")
    void pushAcceptsValidToken() {
        DiskPushRequest request = request();
        when(tokenService.isValidPushToken("secret-token")).thenReturn(true);

        controller.pushDisks("secret-token", request, "10.0.0.2");

        verify(diskService).acceptPush(request, "10.0.0.2");
    }

    @Test
    @DisplayName("查询接口明文返回当前 Push Token")
    void getPushTokenReturnsPlainToken() {
        when(tokenService.getOrCreatePushToken()).thenReturn(new DiskPushTokenResponse("visible-token"));

        DiskPushTokenResponse response = controller.getPushToken();

        assertEquals("visible-token", response.token());
    }

    @Test
    @DisplayName("重置接口明文返回新的 Push Token")
    void regeneratePushTokenReturnsPlainToken() {
        when(tokenService.regeneratePushToken()).thenReturn(new DiskPushTokenResponse("new-visible-token"));

        DiskPushTokenResponse response = controller.regeneratePushToken();

        assertEquals("new-visible-token", response.token());
    }

    private DiskPushRequest request() {
        DiskPushRequest request = new DiskPushRequest();
        request.setHostId("nas-main");
        request.setHostName("NAS Main");
        request.setSampledAt(Instant.parse("2026-05-12T02:20:00Z"));
        request.setDisks(List.of(new DiskPushRequest.DiskSample("sda", "smartctl output")));
        return request;
    }
}
