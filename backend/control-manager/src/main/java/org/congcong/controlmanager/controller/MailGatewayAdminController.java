package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.mail.MailGatewayRequest;
import org.congcong.controlmanager.dto.mail.MailGatewayResponse;
import org.congcong.controlmanager.dto.mail.ToggleStatusRequest;
import org.congcong.controlmanager.service.MailGatewayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/gateways")
@Validated
@RequiredArgsConstructor
public class MailGatewayAdminController {

    private final MailGatewayService mailGatewayService;

    @GetMapping
    public List<MailGatewayResponse> list() {
        return mailGatewayService.listGateways();
    }

    @PostMapping
    public ResponseEntity<MailGatewayResponse> create(@Valid @RequestBody MailGatewayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mailGatewayService.createGateway(request));
    }

    @PutMapping("/{id}")
    public MailGatewayResponse update(@PathVariable Long id, @Valid @RequestBody MailGatewayRequest request) {
        return mailGatewayService.updateGateway(id, request);
    }

    @PostMapping("/{id}/enable")
    public MailGatewayResponse toggle(@PathVariable Long id, @Valid @RequestBody ToggleStatusRequest request) {
        return mailGatewayService.toggleGateway(id, request.getEnabled());
    }
}
