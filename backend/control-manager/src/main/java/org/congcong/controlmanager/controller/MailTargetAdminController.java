package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.mail.MailTargetRequest;
import org.congcong.controlmanager.dto.mail.MailTargetResponse;
import org.congcong.controlmanager.service.MailGatewayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/targets")
@Validated
@RequiredArgsConstructor
public class MailTargetAdminController {

    private final MailGatewayService mailGatewayService;

    @GetMapping
    public List<MailTargetResponse> list(@RequestParam(required = false) String bizKey) {
        return mailGatewayService.listTargets(bizKey);
    }

    @GetMapping("/{bizKey}")
    public MailTargetResponse detail(@PathVariable String bizKey) {
        return mailGatewayService.getTargetByBizKey(bizKey);
    }

    @PostMapping
    public ResponseEntity<MailTargetResponse> create(@Valid @RequestBody MailTargetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mailGatewayService.createTarget(request));
    }

    @PutMapping("/{id}")
    public MailTargetResponse update(@PathVariable Long id, @Valid @RequestBody MailTargetRequest request) {
        return mailGatewayService.updateTarget(id, request);
    }
}
