package org.congcong.controlmanager.controller;

import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.dto.mail.MailSendLogDTO;
import org.congcong.controlmanager.enums.MailSendStatus;
import org.congcong.controlmanager.service.MailSendService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/admin/send-logs")
@Validated
@RequiredArgsConstructor
public class MailSendLogController {

    private final MailSendService mailSendService;

    @GetMapping
    public PageResponse<MailSendLogDTO> list(@RequestParam(required = false) String bizKey,
                                             @RequestParam(required = false) MailSendStatus status,
                                             @RequestParam(required = false) String timeRange,
                                             @RequestParam(defaultValue = "1") @Min(1) int page,
                                             @RequestParam(defaultValue = "10") @Min(1) int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        LocalDateTime startAt = null;
        LocalDateTime endAt = null;
        if (timeRange != null && !timeRange.isEmpty()) {
            String[] parts = timeRange.split(",");
            if (parts.length == 2) {
                startAt = parseDateTime(parts[0].trim(), true);
                endAt = parseDateTime(parts[1].trim(), false);
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timeRange format: 2024-01-01,2024-01-31");
            }
        }
        return mailSendService.queryLogs(bizKey, status, startAt, endAt, pageable);
    }

    private LocalDateTime parseDateTime(String text, boolean isStart) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            if (text.length() == 10) { // yyyy-MM-dd
                LocalDate date = LocalDate.parse(text);
                return isStart ? date.atStartOfDay() : date.plusDays(1).atStartOfDay().minusNanos(1);
            }
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timeRange");
        }
    }
}
