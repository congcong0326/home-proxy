package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.AccessLog;
import org.congcong.common.dto.AuthLog;
import org.congcong.controlmanager.service.LogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Validated
public class LogController {

    private final LogService logService;

    /**
     * 接收访问日志批量
     */
    @PostMapping("/access")
    public ResponseEntity<Map<String, Object>> ingestAccessLogs(@RequestBody List<AccessLog> logs) {
        int saved = logService.saveAccessLogs(logs);
        Map<String, Object> resp = new HashMap<>();
        resp.put("saved", saved);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
    }

    /**
     * 接收认证日志批量
     */
    @PostMapping("/auth")
    public ResponseEntity<Map<String, Object>> ingestAuthLogs(@RequestBody List<AuthLog> logs) {
        int saved = logService.saveAuthLogs(logs);
        Map<String, Object> resp = new HashMap<>();
        resp.put("saved", saved);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
    }
}