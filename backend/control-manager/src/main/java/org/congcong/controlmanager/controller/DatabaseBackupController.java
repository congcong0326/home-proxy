package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.service.SqliteBackupService;
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
@RequestMapping("/api/system-ops/database-backup")
@RequiredArgsConstructor
public class DatabaseBackupController {
    private static final DateTimeFormatter FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SqliteBackupService backupService;

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportBackup() {
        String filename = "home-proxy-" + FILENAME_TIME.format(LocalDateTime.now()) + ".db";
        StreamingResponseBody body = backupService::exportBackup;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }

    /** Stages the uploaded backup; it replaces the live database when the controller restarts. */
    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> restoreBackup(@RequestPart("file") MultipartFile file,
                                             @RequestParam("confirmationPhrase") String confirmationPhrase) {
        backupService.restoreBackup(file, confirmationPhrase);
        return Map.of("message", "restore staged; restart the controller to apply it");
    }
}
