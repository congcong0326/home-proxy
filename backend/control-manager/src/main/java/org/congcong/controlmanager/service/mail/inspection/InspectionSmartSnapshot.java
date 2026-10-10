package org.congcong.controlmanager.service.mail.inspection;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads retained smartctl facts without treating missing or vendor-specific attributes as zero. */
record InspectionSmartSnapshot(String serial, String health, Integer temperature, Integer lifeUsed,
                               BigInteger criticalWarning, BigInteger readBytes, BigInteger writtenBytes,
                               Map<String, BigInteger> errors) {
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\S+)\\s+\\S+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\S+\\s+\\S+\\s+\\S+\\s+([\\d,]+)", Pattern.MULTILINE);

    static InspectionSmartSnapshot parse(String raw, String diskType) {
        String source = raw == null ? "" : raw;
        Map<String, BigInteger> attributes = new LinkedHashMap<>();
        Integer ataTemperature = null;
        var matcher = ATTRIBUTE.matcher(source);
        while (matcher.find()) {
            BigInteger value = new BigInteger(matcher.group(3).replace(",", ""));
            attributes.put(matcher.group(2), value);
            // Vendor attribute 231 can share this name while reporting a placeholder zero.
            // Only ATA attribute 194 identifies the measured temperature; row order is irrelevant.
            if ("194".equals(matcher.group(1)) && "Temperature_Celsius".equals(matcher.group(2))) {
                ataTemperature = intValue(value);
            }
        }
        Integer temperature = numeric(source, "Temperature:", false);
        if (temperature == null) temperature = ataTemperature;
        String health = match(source, "(?m)^SMART (?:overall-health self-assessment test result|Health Status):\\s*(\\S+)");
        String serial = match(source, "(?m)^Serial Number:\\s*(.+)$");
        var errors = new LinkedHashMap<String, BigInteger>();
        BigInteger read = null, written = null, warning = null;
        Integer used = null;
        if ("NVME_SSD".equals(diskType)) {
            used = numeric(source, "Percentage Used:", false);
            warning = number(source, "Critical Warning:", true);
            read = multiply(number(source, "Data Units Read:", false), 512000);
            written = multiply(number(source, "Data Units Written:", false), 512000);
            errors.put("媒体与数据完整性错误", number(source, "Media and Data Integrity Errors:", false));
            errors.put("异常断电次数", number(source, "Unsafe Shutdowns:", false));
        } else if ("SATA_SSD".equals(diskType)) {
            // Only attributes with explicit semantics/units are accepted; attribute IDs alone are insufficient.
            Integer left = intValue(attributes.get("SSD_Life_Left"));
            if (left != null && left >= 0 && left <= 100) used = 100 - left;
            read = multiply(attributes.get("Lifetime_Reads_GiB"), 1L << 30);
            written = multiply(attributes.get("Lifetime_Writes_GiB"), 1L << 30);
            errors.put("已报告不可修复错误", attributes.get("Reported_Uncorrect"));
            errors.put("CRC 错误", attributes.get("UDMA_CRC_Error_Count"));
        } else {
            read = multiply(attributes.get("Total_LBAs_Read"), 512);
            written = multiply(attributes.get("Total_LBAs_Written"), 512);
            errors.put("重映射扇区", attributes.get("Reallocated_Sector_Ct"));
            errors.put("待处理扇区", attributes.get("Current_Pending_Sector"));
            errors.put("不可修复扇区", attributes.get("Offline_Uncorrectable"));
            errors.put("CRC 错误", attributes.get("UDMA_CRC_Error_Count"));
        }
        return new InspectionSmartSnapshot(serial, health, temperature, used, warning, read, written, errors);
    }

    private static BigInteger multiply(BigInteger value, long factor) {
        return value == null ? null : value.multiply(BigInteger.valueOf(factor));
    }

    private static Integer intValue(BigInteger value) {
        return value == null ? null : value.intValueExact();
    }

    private static Integer numeric(String source, String label, boolean hex) {
        return intValue(number(source, label, hex));
    }

    private static BigInteger number(String source, String label, boolean hex) {
        String value = match(source, "(?m)^\\s*" + Pattern.quote(label) + "\\s*(" + (hex ? "0x[0-9a-fA-F]+" : "[\\d,]+") + ")");
        return value == null ? null : new BigInteger(hex ? value.substring(2) : value.replace(",", ""), hex ? 16 : 10);
    }

    private static String match(String source, String expression) {
        var matcher = Pattern.compile(expression).matcher(source);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
