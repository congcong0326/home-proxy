package org.congcong.controlmanager.service.mail;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.mail.MailBizTypeRequest;
import org.congcong.controlmanager.dto.mail.MailSendRequest;
import org.congcong.controlmanager.entity.disk.DiskInfo;
import org.congcong.controlmanager.entity.disk.DiskIoStats;
import org.congcong.controlmanager.service.DiskService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DiskAlertMailHandler implements MailBizHandler {

    private static final String BIZ_KEY = "ops.alert";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DiskService diskService;

    @Override
    public boolean supports(String bizKey) {
        return BIZ_KEY.equalsIgnoreCase(bizKey);
    }

    @Override
    public MailSendRequest build(MailBizTypeRequest request) {
        List<DiskInfo> disks = diskService.getAllDisks();
        List<DiskIoStats> ioStats = diskService.getDailyIoStats();
        String capturedAt = DATE_TIME_FORMATTER.format(LocalDateTime.now());

        StringBuilder content = new StringBuilder();
        content.append("<html><body>");
        content.append("<h2>磁盘监控告警/状态</h2>");
        content.append("<p>采集时间：").append(capturedAt).append("</p>");
        if (disks == null || disks.isEmpty()) {
            content.append("<p>当前环境未检测到磁盘信息或未支持 smartctl/lsblk。</p>");
        } else {
            content.append("<table style=\"border-collapse:collapse;min-width:480px\" border=\"1\" cellpadding=\"6\">");
            content.append("<tr><th>设备</th><th>型号</th><th>容量</th><th>健康</th><th>温度</th><th>序列号</th></tr>");
            for (DiskInfo d : disks) {
                content.append("<tr>")
                        .append("<td>").append(safe(d.device())).append("</td>")
                        .append("<td>").append(safe(d.model())).append("</td>")
                        .append("<td>").append(safe(d.size())).append("</td>")
                        .append("<td>").append(safe(d.status())).append("</td>")
                        .append("<td>").append(formatTemp(d.temperature())).append("</td>")
                        .append("<td>").append(safe(d.serial())).append("</td>")
                        .append("</tr>");
            }
            content.append("</table>");
            content.append("<p>异常提示：建议关注健康状态非 PASSED 或温度持续过高的设备。</p>");

            content.append("<h3>过去24小时读写统计</h3>");
            if (ioStats == null || ioStats.isEmpty()) {
                content.append("<p>暂无读写统计数据。</p>");
            } else {
                content.append("<table style=\"border-collapse:collapse;min-width:480px\" border=\"1\" cellpadding=\"6\">");
                content.append("<tr><th>设备</th><th>读总量</th><th>写总量</th></tr>");
                for (DiskIoStats stat : ioStats) {
                    content.append("<tr>")
                            .append("<td>").append(safe(stat.device())).append("</td>")
                            .append("<td>").append(formatBytes(stat.totalRead())).append("</td>")
                            .append("<td>").append(formatBytes(stat.totalWrite())).append("</td>")
                            .append("</tr>");
                }
                content.append("</table>");
                content.append("<p style=\"color:#666;font-size:12px;\">10分钟粒度采样，读写为相邻采样差值累加。</p>");
            }
        }
        content.append("</body></html>");

        MailSendRequest send = new MailSendRequest();
        send.setBizKey(BIZ_KEY);
        send.setSubject("磁盘监控 - " + capturedAt);
        send.setContentType("text/html");
        send.setContent(content.toString());
        return send;
    }

    private String safe(String v) {
        if (v == null) return "-";
        return v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formatTemp(int temp) {
        if (temp <= 0) return "-";
        return temp + " C";
    }

    private String formatBytes(long bytes) {
        final String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int idx = 0;
        while (value >= 1024 && idx < units.length - 1) {
            value = value / 1024.0;
            idx++;
        }
        return String.format(java.util.Locale.US, "%.2f %s", value, units[idx]);
    }
}
