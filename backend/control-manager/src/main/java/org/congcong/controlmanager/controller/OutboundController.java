package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.outbound.OutboundRequest;
import org.congcong.controlmanager.dto.outbound.OutboundView;
import org.congcong.controlmanager.service.OutboundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 出站控制器
 */
@RestController
@RequestMapping("/api/outbounds")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;

    @GetMapping
    public ResponseEntity<List<OutboundView>> getOutbounds() {
        return ResponseEntity.ok(outboundService.getOutbounds());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OutboundView> getOutbound(@PathVariable Long id) {
        return ResponseEntity.ok(outboundService.getOutbound(id));
    }

    /** 引用该出站的路由，供删除前确认。 */
    @GetMapping("/{id}/references")
    public ResponseEntity<List<Map<String, Object>>> getReferences(@PathVariable Long id) {
        return ResponseEntity.ok(outboundService.getReferencingRoutes(id).stream()
                .map(route -> Map.<String, Object>of("id", route.getId(), "name", route.getName()))
                .toList());
    }

    @PostMapping
    public ResponseEntity<OutboundView> createOutbound(@Valid @RequestBody OutboundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(outboundService.createOutbound(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OutboundView> updateOutbound(@PathVariable Long id, @Valid @RequestBody OutboundRequest request) {
        return ResponseEntity.ok(outboundService.updateOutbound(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOutbound(@PathVariable Long id) {
        outboundService.deleteOutbound(id);
        return ResponseEntity.noContent().build();
    }
}
