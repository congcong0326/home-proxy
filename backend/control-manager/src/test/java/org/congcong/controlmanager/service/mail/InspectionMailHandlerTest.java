package org.congcong.controlmanager.service.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.controlmanager.dto.mail.MailBizTypeRequest;
import org.congcong.controlmanager.entity.scheduler.ScheduledTask;
import org.congcong.controlmanager.scheduler.MailScheduledTaskHandler;
import org.congcong.controlmanager.service.MailSendService;
import org.congcong.controlmanager.service.mail.inspection.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InspectionMailHandlerTest {
    @Test
    void newBusinessKeyUsesTheExistingScheduledTaskPathAndBuildsOnEachRun() {
        var reports = mock(InspectionReportService.class);
        Instant to = Instant.parse("2026-10-10T01:00:00Z");
        when(reports.build()).thenReturn(new InspectionReport("id", to.minusSeconds(86400), to, ZoneId.of("Asia/Shanghai"), List.of()));
        var handler = new InspectionMailHandler(reports, new InspectionMailRenderer());
        var factory = new MailBizHandlerFactory(List.of(handler));
        assertSame(handler, factory.getHandler("ops.inspection"));
        var sender = mock(MailSendService.class);
        var task = new ScheduledTask(); task.setTaskKey("daily-inspection"); task.setBizKey("ops.inspection");
        task.setConfigJson("{\"bizKey\":\"ops.inspection\"}");
        var runnable = new MailScheduledTaskHandler(factory, sender, new ObjectMapper()).buildTask(task);
        verifyNoInteractions(reports);
        runnable.run(); runnable.run();
        verify(reports, times(2)).build();
        verify(sender, times(2)).send(argThat(mail -> mail.getBizKey().equals("ops.inspection")
                && mail.getSubject().contains("2026-10-10 09:00 +08:00")
                && mail.getPlainTextContent().contains("过去 24 小时")));
        assertTrue(handler.supports("OPS.INSPECTION"));
        assertFalse(handler.supports("ops.traffic_report"));
    }
}
