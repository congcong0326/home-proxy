package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.dto.disk.DiskPushRequest;
import org.congcong.controlmanager.dto.disk.DiskPushTokenResponse;
import org.congcong.controlmanager.entity.disk.DiskDetail;
import org.congcong.controlmanager.entity.disk.DiskHost;
import org.congcong.controlmanager.entity.disk.DiskInfo;
import org.congcong.controlmanager.service.DiskMonitorTokenService;
import org.congcong.controlmanager.service.DiskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class DiskMonitorController {

    private final DiskService diskService;

    private final DiskMonitorTokenService tokenService;

    @GetMapping("/disk/hosts")
    public List<DiskHost> getHosts() {
        return diskService.getHosts();
    }

    @GetMapping("/disk/list")
    public List<DiskInfo> getAllDisks(@RequestParam(required = false) String hostId) {
        return diskService.getAllDisks(hostId);
    }

    @GetMapping("/disk/detail/{device}")
    public DiskDetail getDiskDetail(@PathVariable String device, @RequestParam(required = false) String hostId) {
        return diskService.getDiskDetail(hostId, device);
    }

    @GetMapping("/disk/push-token")
    public DiskPushTokenResponse getPushToken() {
        return tokenService.getOrCreatePushToken();
    }

    @PostMapping("/disk/push-token/regenerate")
    public DiskPushTokenResponse regeneratePushToken() {
        return tokenService.regeneratePushToken();
    }

    @PostMapping("/disk/push")
    public ResponseEntity<Map<String, Object>> pushDisks(
            @RequestHeader(value = "X-Disk-Push-Token", required = false) String pushToken,
            @Valid @RequestBody DiskPushRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String sourceIp) {
        verifyPushToken(pushToken);
        diskService.acceptPush(request, sourceIp);
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }

    private void verifyPushToken(String pushToken) {
        if (!tokenService.isValidPushToken(pushToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid disk push token");
        }
    }

}
