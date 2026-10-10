package org.congcong.controlmanager.controller;

import org.congcong.controlmanager.dto.mail.MailBizTypeRequest;
import org.congcong.controlmanager.dto.mail.MailSendRequest;
import org.congcong.controlmanager.service.MailSendService;
import org.congcong.controlmanager.service.mail.MailBizHandlerFactory;
import org.congcong.controlmanager.service.mail.MailBizHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MailPreviewControllerTest {
    @Test
    void previewBuildsTheInspectionWithoutSendingOrRequiringARecipient() throws Exception {
        var sender = mock(MailSendService.class); var factory = mock(MailBizHandlerFactory.class);
        var handler = mock(MailBizHandler.class);
        var request = new MailSendRequest(); request.setBizKey("ops.inspection"); request.setSubject("巡检");
        request.setContentType("text/html"); request.setContent("<p>巡检内容</p>"); request.setPlainTextContent("巡检内容");
        when(factory.getHandler("ops.inspection")).thenReturn(handler);
        when(handler.build(any(MailBizTypeRequest.class))).thenReturn(request);
        var mvc = MockMvcBuilders.standaloneSetup(new InternalMailController(sender, factory)).build();
        mvc.perform(post("/api/mail/preview").contentType(MediaType.APPLICATION_JSON).content("{\"bizKey\":\"ops.inspection\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bizKey").value("ops.inspection"))
                .andExpect(jsonPath("$.plainTextContent").value("巡检内容"));
        verifyNoInteractions(sender);
        mvc.perform(post("/api/mail/preview").contentType(MediaType.APPLICATION_JSON).content("{\"bizKey\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
