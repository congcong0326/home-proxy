package org.congcong.controlmanager.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.dto.mail.MailSendRequest;
import org.congcong.controlmanager.dto.mail.MailSendResponse;
import org.congcong.controlmanager.dto.mail.MailSendLogDTO;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.entity.mail.MailGateway;
import org.congcong.controlmanager.entity.mail.MailSendLog;
import org.congcong.controlmanager.entity.mail.MailTarget;
import org.congcong.controlmanager.enums.MailSendStatus;
import org.congcong.controlmanager.repository.mail.MailGatewayRepository;
import org.congcong.controlmanager.repository.mail.MailSendLogRepository;
import org.congcong.controlmanager.repository.mail.MailTargetRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Properties;

@Service
@RequiredArgsConstructor
@Slf4j
public class MailSendService {

    private final MailTargetRepository targetRepository;
    private final MailGatewayRepository gatewayRepository;
    private final MailSendLogRepository mailSendLogRepository;

    public PageResponse<MailSendLogDTO> queryLogs(String bizKey, MailSendStatus status, LocalDateTime startAt, LocalDateTime endAt, Pageable pageable) {
        Page<MailSendLog> page = mailSendLogRepository.queryLogs(bizKey, status, startAt, endAt, pageable);
        return new PageResponse<>(
                page.getContent().stream().map(this::toDto).toList(),
                pageable.getPageNumber() + 1,
                pageable.getPageSize(),
                page.getTotalElements()
        );
    }

    public MailSendResponse send(MailSendRequest request) {
        MailTarget target = targetRepository.findByBizKeyAndEnabledTrue(request.getBizKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "bizKey not found or disabled"));

        if (StringUtils.hasText(request.getRequestId())) {
            MailSendLog existing = mailSendLogRepository.findTopByBizKeyAndRequestIdOrderByIdDesc(request.getBizKey(), request.getRequestId()).orElse(null);
            if (existing != null) {
                return new MailSendResponse(existing.getId(), existing.getStatus(), existing.getErrorMessage());
            }
        }

        MailGateway gateway = resolveGateway(target);

        MailSendLog logEntry = new MailSendLog();
        logEntry.setBizKey(target.getBizKey());
        logEntry.setGatewayId(gateway.getId());
        logEntry.setToList(target.getToList());
        logEntry.setCcList(target.getCcList());
        logEntry.setBccList(target.getBccList());
        logEntry.setSubject(request.getSubject());
        String contentType = normalizeContentType(request.getContentType());
        logEntry.setContentType(contentType);
        logEntry.setContentSize(request.getContent() == null ? 0 : request.getContent().getBytes(StandardCharsets.UTF_8).length);
        logEntry.setRequestId(request.getRequestId());
        logEntry.setCreatedAt(LocalDateTime.now());

        try {
            sendMail(gateway, target, request, contentType);
            logEntry.setStatus(MailSendStatus.SUCCESS);
            mailSendLogRepository.save(logEntry);
            return new MailSendResponse(logEntry.getId(), MailSendStatus.SUCCESS, null);
        } catch (Exception e) {
            log.warn("Send mail failed for bizKey {}", target.getBizKey(), e);
            logEntry.setStatus(MailSendStatus.FAILED);
            logEntry.setErrorMessage(truncate(e.getMessage(), 1000));
            mailSendLogRepository.save(logEntry);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "mail send failed: " + e.getMessage());
        }
    }

    private MailGateway resolveGateway(MailTarget target) {
        if (target.getGatewayId() != null) {
            return gatewayRepository.findByIdAndEnabledTrue(target.getGatewayId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "gateway disabled or not found"));
        }
        return gatewayRepository.findFirstByEnabledTrueOrderByIdAsc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no enabled gateway"));
    }

    private void sendMail(MailGateway gateway, MailTarget target, MailSendRequest request, String normalizedContentType) throws Exception {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(gateway.getHost());
        mailSender.setPort(gateway.getPort());
        mailSender.setUsername(gateway.getUsername());
        mailSender.setPassword(gateway.getPassword());
        mailSender.setProtocol(gateway.getProtocol());
        mailSender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties properties = mailSender.getJavaMailProperties();
        properties.put("mail.transport.protocol", gateway.getProtocol());
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", Boolean.toString(gateway.isStarttlsEnabled()));
        properties.put("mail.smtp.ssl.enable", Boolean.toString(gateway.isSslEnabled()));
        properties.put("mail.smtp.ssl.trust", gateway.getHost());

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(StringUtils.hasText(gateway.getFromAddress()) ? gateway.getFromAddress() : gateway.getUsername());

        String[] to = splitAddresses(target.getToList());
        if (to.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "toList is empty");
        }
        helper.setTo(to);

        String[] cc = splitAddresses(target.getCcList());
        if (cc.length > 0) {
            helper.setCc(cc);
        }
        String[] bcc = splitAddresses(target.getBccList());
        if (bcc.length > 0) {
            helper.setBcc(bcc);
        }
        helper.setSubject(request.getSubject());
        boolean isHtml = "text/html".equalsIgnoreCase(normalizedContentType);
        helper.setText(request.getContent(), isHtml);
        mailSender.send(message);
    }

    private String[] splitAddresses(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new String[0];
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
    }

    private String normalizeContentType(String contentType) {
        if (contentType != null && "text/html".equalsIgnoreCase(contentType)) {
            return "text/html";
        }
        return "text/plain";
    }

    private MailSendLogDTO toDto(MailSendLog log) {
        return new MailSendLogDTO(
                log.getId(),
                log.getBizKey(),
                log.getGatewayId(),
                log.getToList(),
                log.getCcList(),
                log.getBccList(),
                log.getSubject(),
                log.getContentType(),
                log.getContentSize(),
                log.getStatus(),
                log.getErrorMessage(),
                log.getRequestId(),
                log.getCreatedAt()
        );
    }

    private String truncate(String message, int max) {
        if (message == null) {
            return null;
        }
        if (message.length() <= max) {
            return message;
        }
        return message.substring(0, max);
    }
}
