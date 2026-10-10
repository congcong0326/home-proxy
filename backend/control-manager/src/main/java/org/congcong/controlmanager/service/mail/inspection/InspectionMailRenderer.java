package org.congcong.controlmanager.service.mail.inspection;

import org.springframework.stereotype.Component;

import static org.congcong.controlmanager.service.mail.inspection.InspectionFormats.time;

@Component
public class InspectionMailRenderer {
    public String plainText(InspectionReport report) {
        var body = new StringBuilder("每日巡检报告\n");
        body.append("报告 ID：").append(report.reportId()).append('\n');
        body.append("统计窗口：").append(time(report.from(), report.zone())).append(" 至 ")
                .append(time(report.to(), report.zone())).append("（起含止不含，过去 24 小时）\n");
        body.append("生成时间：").append(time(report.to(), report.zone())).append("；时区：").append(report.zone()).append('\n');
        for (var section : report.sections()) {
            body.append("\n").append(section.title()).append('\n');
            for (var item : section.items()) {
                body.append("\n").append(item.title()).append('\n');
                item.fields().forEach((field, value) -> body.append(field).append("：").append(value).append('\n'));
            }
            for (String note : section.notes()) body.append("说明：").append(note).append('\n');
        }
        return body.toString();
    }

    public String html(InspectionReport report) {
        var body = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>")
                .append("<body style=\"font-family:Arial,sans-serif;color:#243039;max-width:900px;margin:24px auto;padding:0 12px\">")
                .append("<h1>每日巡检报告</h1><p>报告 ID：").append(escape(report.reportId())).append("</p>")
                .append("<p>统计窗口：").append(escape(time(report.from(), report.zone()))).append(" 至 ")
                .append(escape(time(report.to(), report.zone()))).append("（起含止不含，过去 24 小时）</p>")
                .append("<p>生成时间：").append(escape(time(report.to(), report.zone()))).append("；时区：")
                .append(escape(report.zone().getId())).append("</p>");
        for (var section : report.sections()) {
            body.append("<h2 style=\"border-bottom:2px solid #237884;padding-bottom:8px\">")
                    .append(escape(section.title())).append("</h2>");
            for (var item : section.items()) {
                body.append("<h3>").append(escape(item.title())).append("</h3>")
                        .append("<table style=\"border-collapse:collapse;width:100%;font-size:14px\"><tbody>");
                item.fields().forEach((field, value) -> body
                        .append("<tr><th scope=\"row\" style=\"text-align:left;vertical-align:top;width:30%;border:1px solid #ccd4d8;padding:8px\">")
                        .append(escape(field)).append("</th><td style=\"border:1px solid #ccd4d8;padding:8px;overflow-wrap:anywhere\">")
                        .append(escape(value)).append("</td></tr>"));
                body.append("</tbody></table>");
            }
            for (String note : section.notes()) body.append("<p style=\"font-size:13px;color:#58656f\">说明：")
                    .append(escape(note)).append("</p>");
        }
        return body.append("</body></html>").toString();
    }

    private static String escape(String value) {
        return value == null ? "未知" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
