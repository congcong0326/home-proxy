package org.congcong.controlmanager.service;

import jakarta.mail.Multipart;
import org.congcong.controlmanager.dto.mail.MailSendRequest;
import org.congcong.controlmanager.entity.mail.MailGateway;
import org.congcong.controlmanager.entity.mail.MailTarget;
import org.congcong.controlmanager.repository.mail.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailSendServiceTest {
    @Test
    void sendsPlainAndHtmlAlternativesInTheSameInspectionMessage() throws Exception {
        var targets = mock(MailTargetRepository.class); var gateways = mock(MailGatewayRepository.class);
        var logs = mock(MailSendLogRepository.class);
        var target = new MailTarget(); target.setBizKey("ops.inspection"); target.setToList("reader@example.test");
        var gateway = new MailGateway(); gateway.setId(1L); gateway.setHost("smtp.example.test"); gateway.setPort(587);
        gateway.setUsername("sender@example.test"); gateway.setPassword("test-only"); gateway.setProtocol("smtp");
        var service = new MailSendService(targets, gateways, logs);
        var request = new MailSendRequest(); request.setBizKey("ops.inspection"); request.setSubject("巡检");
        request.setContentType("text/html"); request.setContent("<p>完整巡检</p>"); request.setPlainTextContent("完整巡检");
        var message = service.buildMessage(new JavaMailSenderImpl(), gateway, target, request, "text/html");
        message.saveChanges();
        assertTrue(hasPart(message.getContent(), "text/plain", "完整巡检"));
        assertTrue(hasPart(message.getContent(), "text/html", "<p>完整巡检</p>"));
        assertEquals("reader@example.test", message.getAllRecipients()[0].toString());
        request.setPlainTextContent(null);
        var legacy = service.buildMessage(new JavaMailSenderImpl(), gateway, target, request, "text/html");
        legacy.saveChanges();
        assertTrue(legacy.isMimeType("text/html"));
        assertEquals("<p>完整巡检</p>", legacy.getContent());
        verifyNoInteractions(targets, gateways, logs);
    }

    private boolean hasPart(Object content, String type, String expected) throws Exception {
        if (!(content instanceof Multipart multipart)) return false;
        for (int i = 0; i < multipart.getCount(); i++) {
            var part = multipart.getBodyPart(i);
            if (part.isMimeType(type) && expected.equals(part.getContent())) return true;
            if (hasPart(part.getContent(), type, expected)) return true;
        }
        return false;
    }
}
