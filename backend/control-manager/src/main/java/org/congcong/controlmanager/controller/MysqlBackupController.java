package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.service.MysqlBackupService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/system-ops/mysql-backup")
@RequiredArgsConstructor
public class MysqlBackupController {
    private static final DateTimeFormatter FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MysqlBackupService mysqlBackupService;

    @GetMapping(value = "/export", produces = "application/gzip")
    public ResponseEntity<StreamingResponseBody> exportBackup() {
        String filename = "home-proxy-mysql-" + FILENAME_TIME.format(LocalDateTime.now()) + ".sql.gz";
        StreamingResponseBody body = mysqlBackupService::exportBackup;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/gzip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }

    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> restoreBackup(@RequestPart("file") MultipartFile file,
                                             @RequestParam("confirmationPhrase") String confirmationPhrase) {
        mysqlBackupService.restoreBackup(file, confirmationPhrase);
        return Map.of("message", "restore completed");
    }
}
