package org.congcong.controlmanager.service.mail;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.mail.MailBizTypeRequest;
import org.congcong.controlmanager.dto.mail.MailSendRequest;
import org.congcong.controlmanager.service.mail.inspection.InspectionMailRenderer;
import org.congcong.controlmanager.service.mail.inspection.InspectionReportService;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class InspectionMailHandler implements MailBizHandler {
    public static final String BIZ_KEY = "ops.inspection";
    private final InspectionReportService reports;
    private final InspectionMailRenderer renderer;

    @Override
    public boolean supports(String bizKey) { return BIZ_KEY.equalsIgnoreCase(bizKey); }

    @Override
    public MailSendRequest build(MailBizTypeRequest request) {
        var report = reports.build();
        var send = new MailSendRequest();
        send.setBizKey(BIZ_KEY);
        send.setSubject("[每日巡检] " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX")
                .format(report.to().atZone(report.zone())));
        send.setContentType("text/html");
        send.setContent(renderer.html(report));
        send.setPlainTextContent(renderer.plainText(report));
        return send;
    }
}
