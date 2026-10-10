package org.congcong.controlmanager.service.mail.inspection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered, explicit inspection facts shared by the HTML and plain text renderers. */
public record InspectionSection(String title, List<Item> items, List<String> notes) {
    public record Item(String title, Map<String, String> fields) { }

    public static Item item(String title, String... fields) {
        if (fields.length % 2 != 0) throw new IllegalArgumentException("Expected field/value pairs");
        var values = new LinkedHashMap<String, String>();
        for (int i = 0; i < fields.length; i += 2) values.put(fields[i], fields[i + 1]);
        return new Item(title, values);
    }

    public static InspectionSection unavailable(String title) {
        return new InspectionSection(title, List.of(), List.of("数据不可用：本项查询失败，无法判断状态。"));
    }
}
