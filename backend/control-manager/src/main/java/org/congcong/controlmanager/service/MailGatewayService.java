package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.mail.MailGatewayRequest;
import org.congcong.controlmanager.dto.mail.MailGatewayResponse;
import org.congcong.controlmanager.dto.mail.MailTargetRequest;
import org.congcong.controlmanager.dto.mail.MailTargetResponse;
import org.congcong.controlmanager.entity.mail.MailGateway;
import org.congcong.controlmanager.entity.mail.MailTarget;
import org.congcong.controlmanager.repository.mail.MailGatewayRepository;
import org.congcong.controlmanager.repository.mail.MailTargetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MailGatewayService {

    private final MailGatewayRepository gatewayRepository;
    private final MailTargetRepository targetRepository;

    public List<MailGatewayResponse> listGateways() {
        return gatewayRepository.findAll().stream().map(this::toGatewayResponse).toList();
    }

    @Transactional
    public MailGatewayResponse createGateway(MailGatewayRequest request) {
        MailGateway gateway = new MailGateway();
        applyGateway(request, gateway);
        MailGateway saved = gatewayRepository.save(gateway);
        return toGatewayResponse(saved);
    }

    @Transactional
    public MailGatewayResponse updateGateway(Long id, MailGatewayRequest request) {
        MailGateway gateway = gatewayRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gateway not found"));
        applyGateway(request, gateway);
        MailGateway saved = gatewayRepository.save(gateway);
        return toGatewayResponse(saved);
    }

    @Transactional
    public MailGatewayResponse toggleGateway(Long id, boolean enabled) {
        MailGateway gateway = gatewayRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gateway not found"));
        gateway.setEnabled(enabled);
        MailGateway saved = gatewayRepository.save(gateway);
        return toGatewayResponse(saved);
    }

    public List<MailTargetResponse> listTargets(String bizKey) {
        List<MailTarget> targets;
        if (StringUtils.hasText(bizKey)) {
            targets = targetRepository.findByBizKeyContainingIgnoreCase(bizKey);
        } else {
            targets = targetRepository.findAll();
        }
        return targets.stream().map(this::toTargetResponse).toList();
    }

    public MailTargetResponse getTargetByBizKey(String bizKey) {
        MailTarget target = targetRepository.findByBizKey(bizKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target not found"));
        return toTargetResponse(target);
    }

    @Transactional
    public MailTargetResponse createTarget(MailTargetRequest request) {
        if (targetRepository.existsByBizKey(request.getBizKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bizKey already exists");
        }
        MailTarget target = new MailTarget();
        applyTarget(request, target);
        MailTarget saved = targetRepository.save(target);
        return toTargetResponse(saved);
    }

    @Transactional
    public MailTargetResponse updateTarget(Long id, MailTargetRequest request) {
        MailTarget target = targetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target not found"));
        if (targetRepository.existsByBizKeyAndIdNot(request.getBizKey(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bizKey already exists");
        }
        applyTarget(request, target);
        MailTarget saved = targetRepository.save(target);
        return toTargetResponse(saved);
    }

    public MailGatewayResponse toGatewayResponse(MailGateway gateway) {
        return new MailGatewayResponse(
                gateway.getId(),
                gateway.getName(),
                gateway.getHost(),
                gateway.getPort(),
                gateway.getUsername(),
                gateway.getProtocol(),
                gateway.isSslEnabled(),
                gateway.isStarttlsEnabled(),
                gateway.getFromAddress(),
                gateway.isEnabled(),
                gateway.getCreatedAt(),
                gateway.getUpdatedAt()
        );
    }

    public MailTargetResponse toTargetResponse(MailTarget target) {
        return new MailTargetResponse(
                target.getId(),
                target.getBizKey(),
                target.getToList(),
                target.getCcList(),
                target.getBccList(),
                target.getGatewayId(),
                target.isEnabled(),
                target.getCreatedAt(),
                target.getUpdatedAt()
        );
    }

    private void applyGateway(MailGatewayRequest request, MailGateway gateway) {
        gateway.setName(request.getName());
        gateway.setHost(request.getHost());
        gateway.setPort(request.getPort());
        gateway.setUsername(request.getUsername());
        gateway.setPassword(request.getPassword());
        gateway.setProtocol(StringUtils.hasText(request.getProtocol()) ? request.getProtocol() : "smtp");
        gateway.setSslEnabled(request.isSslEnabled());
        gateway.setStarttlsEnabled(request.isStarttlsEnabled());
        gateway.setFromAddress(request.getFromAddress());
        gateway.setEnabled(request.isEnabled());
    }

    private void applyTarget(MailTargetRequest request, MailTarget target) {
        target.setBizKey(request.getBizKey());
        target.setToList(request.getToList());
        target.setCcList(request.getCcList());
        target.setBccList(request.getBccList());
        target.setGatewayId(request.getGatewayId());
        target.setEnabled(request.isEnabled());
        if (request.getGatewayId() != null && !gatewayRepository.existsById(request.getGatewayId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gatewayId not found");
        }
    }
}
