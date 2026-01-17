package org.congcong.controlmanager.service.mail;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.TopItem;
import org.congcong.controlmanager.dto.mail.MailBizTypeRequest;
import org.congcong.controlmanager.dto.mail.MailSendRequest;
import org.congcong.controlmanager.service.LogService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TrafficReportMailHandler implements MailBizHandler {

    private static final String BIZ_KEY = "ops.traffic_report";
    private static final String METRIC = "bytes";
    private static final int TOP_LIMIT = 10;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LogService logService;

    @Override
    public boolean supports(String bizKey) {
        return BIZ_KEY.equalsIgnoreCase(bizKey);
    }

    @Override
    public MailSendRequest build(MailBizTypeRequest request) {
        LocalDate reportDate = LocalDate.now().minusDays(1);
        LocalDateTime from = reportDate.atStartOfDay();
        LocalDateTime to = reportDate.plusDays(1).atStartOfDay().minusSeconds(1);
        String fromStr = from.toString();
        String toStr = to.toString();

        List<TopItem> userTop = logService.aggregateDailyTopRange(fromStr, toStr, "users", METRIC, TOP_LIMIT, null);
        List<TopItem> appTop = logService.aggregateDailyTopRange(fromStr, toStr, "apps", METRIC, TOP_LIMIT, null);
        List<TopItem> routeTop = logService.aggregateDailyTopRange(fromStr, toStr, "route_policies", METRIC, TOP_LIMIT, null);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        long todayTotalBytes = logService.sumBytes(todayStart, now);
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long monthTotalBytes = logService.sumBytes(monthStart, now);

        StringBuilder contentBuilder = new StringBuilder();
        contentBuilder.append("<html><body>");
        contentBuilder.append("<h2>流量报表 - ").append(reportDate).append("</h2>");
        contentBuilder.append("<p>统计时间窗口：")
                .append(formatDateTime(from))
                .append(" 至 ")
                .append(formatDateTime(to))
                .append(" (系统时区)</p>");
        contentBuilder.append(buildSummary(todayStart, now, todayTotalBytes, monthStart, now, monthTotalBytes));
        contentBuilder.append(buildSection("按用户 Top" + TOP_LIMIT, userTop));
        contentBuilder.append(buildSection("按应用 Top" + TOP_LIMIT, appTop));
        contentBuilder.append(buildSection("按路由规则 Top" + TOP_LIMIT, routeTop));
        contentBuilder.append("</body></html>");

        MailSendRequest mailSendRequest = new MailSendRequest();
        mailSendRequest.setBizKey(BIZ_KEY);
        mailSendRequest.setSubject("流量报表 - " + reportDate);
        mailSendRequest.setContentType("text/html");
        mailSendRequest.setContent(contentBuilder.toString());
        return mailSendRequest;
    }

    private String buildSection(String title, List<TopItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>").append(escape(title)).append("</h3>");
        sb.append("<table style=\"border-collapse:collapse;min-width:320px\" border=\"1\" cellpadding=\"6\">");
        sb.append("<tr><th>排名</th><th>名称</th><th>流量</th></tr>");
        if (items == null || items.isEmpty()) {
            sb.append("<tr><td colspan=\"3\">暂无数据</td></tr>");
        } else {
            for (int i = 0; i < items.size(); i++) {
                TopItem item = items.get(i);
                sb.append("<tr>")
                        .append("<td>").append(i + 1).append("</td>")
                        .append("<td>").append(escape(Optional.ofNullable(item.getKey()).orElse("-"))).append("</td>")
                        .append("<td>").append(formatBytes(Optional.ofNullable(item.getValue()).orElse(0L))).append("</td>")
                        .append("</tr>");
            }
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildSummary(LocalDateTime todayStart, LocalDateTime now, long todayTotalBytes,
                                LocalDateTime monthStart, LocalDateTime monthEnd, long monthTotalBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>总览</h3>");
        sb.append("<ul>");
        sb.append("<li><strong>今日总流量：</strong>")
                .append(formatBytes(todayTotalBytes))
                .append(" （")
                .append(formatDateTime(todayStart))
                .append(" - ")
                .append(formatDateTime(now))
                .append("）</li>");
        sb.append("<li><strong>本月总流量：</strong>")
                .append(formatBytes(monthTotalBytes))
                .append(" （")
                .append(formatDateTime(monthStart))
                .append(" - ")
                .append(formatDateTime(monthEnd))
                .append("）</li>");
        sb.append("</ul>");
        return sb.toString();
    }

    private String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formatDateTime(LocalDateTime time) {
        return DATE_TIME_FORMATTER.format(time);
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
