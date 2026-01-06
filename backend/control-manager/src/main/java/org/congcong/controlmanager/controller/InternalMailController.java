package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.mail.MailSendRequest;
import org.congcong.controlmanager.dto.mail.MailSendResponse;
import org.congcong.controlmanager.service.MailSendService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/mail")
@Validated
@RequiredArgsConstructor
public class InternalMailController {

    private final MailSendService mailSendService;

    @PostMapping("/send")
    public ResponseEntity<MailSendResponse> send(@Valid @RequestBody MailSendRequest request) {
        return ResponseEntity.ok(mailSendService.send(request));
    }
}
