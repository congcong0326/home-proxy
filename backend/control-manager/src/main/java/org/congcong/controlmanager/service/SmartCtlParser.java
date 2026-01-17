package org.congcong.controlmanager.service;



import org.congcong.controlmanager.entity.disk.DiskDetail;
import org.congcong.controlmanager.entity.disk.DiskInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class SmartCtlParser {
    // 通用模式
    private static final Pattern DEVICE_MODEL = Pattern.compile("(Device Model|Model Number):\\s+(.+)");
    private static final Pattern SERIAL_NUMBER = Pattern.compile("Serial Number:\\s+(.+)");
    private static final Pattern USER_CAPACITY = Pattern.compile("(User Capacity|Total NVM Capacity):\\s+[\\d,]+ bytes \\[(.+?)\\]|Total NVM Capacity:\\s+(.+?) \\[(.+?)\\]");
    private static final Pattern HEALTH_STATUS = Pattern.compile("SMART overall-health self-assessment test result: (\\w+)");
    private static final Pattern SMART_SUPPORTED = Pattern.compile("SMART support is:\\s+Available");
    private static final Pattern SMART_ENABLED = Pattern.compile("SMART support is:\\s+Enabled");
    
    // HDD特有模式
    private static final Pattern TEMPERATURE = Pattern.compile(
            "^194\\s+Temperature_Celsius\\s+(?:\\S+\\s+){7}(\\d+)(?:\\D.*)?|Temperature:\\s+(\\d+) Celsius|^194\\s+Temperature_Celsius\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\d+)",
            Pattern.MULTILINE
    );
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\S+)\\s+\\S+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\S+)", Pattern.MULTILINE);
    
    // NVMe SSD特有模式
    private static final Pattern NVME_PERCENTAGE_USED = Pattern.compile("Percentage Used:\\s+(\\d+)%");
    private static final Pattern NVME_DATA_UNITS_READ = Pattern.compile("Data Units Read:\\s+([\\d,]+)");
    private static final Pattern NVME_DATA_UNITS_WRITTEN = Pattern.compile("Data Units Written:\\s+([\\d,]+)");
    private static final Pattern NVME_POWER_ON_HOURS = Pattern.compile("Power On Hours:\\s+(\\d+)");
    private static final Pattern NVME_POWER_CYCLES = Pattern.compile("Power Cycles:\\s+(\\d+)");
    private static final Pattern NVME_UNSAFE_SHUTDOWNS = Pattern.compile("Unsafe Shutdowns:\\s+(\\d+)");
    private static final Pattern NVME_MEDIA_ERRORS = Pattern.compile("Media and Data Integrity Errors:\\s+(\\d+)");
    
    // SATA SSD特有属性ID
    private static final int ATTR_SSD_LIFE_LEFT = 231;
    private static final int ATTR_FLASH_WRITES_GIB = 233;
    private static final int ATTR_LIFETIME_WRITES_GIB = 241;
    private static final int ATTR_LIFETIME_READS_GIB = 242;
    private static final int ATTR_AVERAGE_ERASE_COUNT = 244;
    private static final int ATTR_MAX_ERASE_COUNT = 245;
    private static final int ATTR_TOTAL_ERASE_COUNT = 246;
    private static final int ATTR_UNSAFE_SHUTDOWN_COUNT = 192;
    private static final int ATTR_REPORTED_UNCORRECT = 187;
    
    public static DiskInfo parseBasicInfo(String device, String infoOutput, String healthOutput, String dataOutput) {
        return new DiskInfo(
                device,
                extractDeviceModel(infoOutput),
                extractGroup(infoOutput, SERIAL_NUMBER),
                extractSize(infoOutput),
                parseHealthStatus(healthOutput),
                parseTemperature(dataOutput)
        );
    }

    public static DiskDetail parseDetail(String device, String fullOutput, List<Integer> historyTemperature) {
        // 判断磁盘类型
        String diskType = determineDiskType(fullOutput);
        
        switch (diskType) {
            case "NVME_SSD":
                return parseNvmeSsdDetail(device, fullOutput, historyTemperature, null, null);
            case "SATA_SSD":
                return parseSataSsdDetail(device, fullOutput, historyTemperature, null, null);
            default: // HDD
                return parseHddDetail(device, fullOutput, historyTemperature, null, null);
        }
    }

    public static DiskDetail parseDetail(String device, String fullOutput, List<Integer> historyTemperature,
                                         List<Long> historyReadBytes, List<Long> historyWriteBytes) {
        // 判断磁盘类型
        String diskType = determineDiskType(fullOutput);

        switch (diskType) {
            case "NVME_SSD":
                return parseNvmeSsdDetail(device, fullOutput, historyTemperature, historyReadBytes, historyWriteBytes);
            case "SATA_SSD":
                return parseSataSsdDetail(device, fullOutput, historyTemperature, historyReadBytes, historyWriteBytes);
            default: // HDD
                return parseHddDetail(device, fullOutput, historyTemperature, historyReadBytes, historyWriteBytes);
        }
    }
    
    private static String determineDiskType(String output) {
        if (output.contains("NVMe") || output.contains("nvme")) {
            return "NVME_SSD";
        } else if (output.contains("Solid State Device") || output.contains("SSD")) {
            return "SATA_SSD";
        } else {
            return "HDD";
        }
    }

    /**
     * Vendor Specific SMART Attributes with Thresholds:
     * ID# ATTRIBUTE_NAME          FLAG     VALUE WORST THRESH TYPE      UPDATED  WHEN_FAILED RAW_VALUE
     *   1 Raw_Read_Error_Rate     0x000b   100   100   016    Pre-fail  Always       -       0
     *   2 Throughput_Performance  0x0005   130   130   054    Pre-fail  Offline      -       100
     *   3 Spin_Up_Time            0x0007   155   155   024    Pre-fail  Always       -       426 (Average 424)
     *   4 Start_Stop_Count        0x0012   100   100   000    Old_age   Always       -       12
     *   5 Reallocated_Sector_Ct   0x0033   100   100   005    Pre-fail  Always       -       0
     *   7 Seek_Error_Rate         0x000b   100   100   067    Pre-fail  Always       -       0
     *   8 Seek_Time_Performance   0x0005   140   140   020    Pre-fail  Offline      -       15
     *   9 Power_On_Hours          0x0012   100   100   000    Old_age   Always       -       4916
     *  10 Spin_Retry_Count        0x0013   100   100   060    Pre-fail  Always       -       0
     *  12 Power_Cycle_Count       0x0032   100   100   000    Old_age   Always       -       12
     *  22 Unknown_Attribute       0x0023   100   100   025    Pre-fail  Always       -       100
     *  45 Unknown_Attribute       0x0023   100   100   001    Pre-fail  Always       -       1095233372415
     * 192 Power-Off_Retract_Count 0x0032   100   100   000    Old_age   Always       -       154
     * 193 Load_Cycle_Count        0x0012   100   100   000    Old_age   Always       -       154
     * 194 Temperature_Celsius     0x0002   176   176   000    Old_age   Always       -       34 (Min/Max 23/51)
     * 196 Reallocated_Event_Count 0x0032   100   100   000    Old_age   Always       -       0
     * 197 Current_Pending_Sector  0x0022   100   100   000    Old_age   Always       -       0
     * 198 Offline_Uncorrectable   0x0008   100   100   000    Old_age   Offline      -       0
     * 199 UDMA_CRC_Error_Count    0x000a   200   200   000    Old_age   Always       -       0
     * 231 Temperature_Celsius     0x0032   100   100   000    Old_age   Always       -       0
     * 241 Total_LBAs_Written      0x0012   100   100   000    Old_age   Always       -       3443820208940
     * 242 Total_LBAs_Read         0x0012   100   100   000    Old_age   Always       -       4574299388498
     * @param device
     * @param fullOutput
     * @param historyTemperature
     * @return
     */
    private static DiskDetail parseHddDetail(String device, String fullOutput, List<Integer> historyTemperature,
                                             List<Long> historyReadBytes, List<Long> historyWriteBytes) {
        Map<String, Long> attributes = parseAttributes(fullOutput);

        // 转换 LBA -> 字节（每 LBA 为 512 字节）
        long totalLbasWritten = attributes.getOrDefault("Total_LBAs_Written", 0L);
        long totalLbasRead = attributes.getOrDefault("Total_LBAs_Read", 0L);
        long bytesPerLba = 512L;
        long dataUnitsWrittenBytes = totalLbasWritten * bytesPerLba;
        long dataUnitsReadBytes = totalLbasRead * bytesPerLba;

        long powerOnHours = attributes.getOrDefault("Power_On_Hours", 0L);
        int powerCycleCount = attributes.getOrDefault("Power_Cycle_Count", 0L).intValue();
        int reallocatedSectorCount = attributes.getOrDefault("Reallocated_Sector_Ct", 0L).intValue();
        int seekErrorRate = attributes.getOrDefault("Seek_Error_Rate", 0L).intValue();
        int spinRetryCount = attributes.getOrDefault("Spin_Retry_Count", 0L).intValue();
        int udmaCrcErrorCount = attributes.getOrDefault("UDMA_CRC_Error_Count", 0L).intValue();
        int unsafeShutdowns = attributes.getOrDefault("Power-Off_Retract_Count", 0L).intValue();
        int mediaErrors = attributes.getOrDefault("Offline_Uncorrectable", 0L).intValue();

        return new DiskDetail(
                device,
                extractDeviceModel(fullOutput),
                extractGroup(fullOutput, SERIAL_NUMBER),
                extractSize(fullOutput),
                parseTemperature(fullOutput),
                extractGroup(fullOutput, HEALTH_STATUS),
                isSmartSupported(fullOutput),
                isSmartEnabled(fullOutput),
                powerOnHours,
                powerCycleCount,
                dataUnitsReadBytes,
                dataUnitsWrittenBytes,
                reallocatedSectorCount,
                seekErrorRate,
                spinRetryCount,
                udmaCrcErrorCount,
                0, // percentageUsed（HDD 不适用）
                unsafeShutdowns,
                mediaErrors,
                0, // ssdLifeLeft（HDD 不适用）
                0, // flashWritesGiB（HDD 不适用）
                0, // lifetimeWritesGiB（HDD 不适用）
                0, // lifetimeReadsGiB（HDD 不适用）
                0, // averageEraseCount
                0, // maxEraseCount
                0, // totalEraseCount
                "HDD",
                historyTemperature,
                historyReadBytes,
                historyWriteBytes
        );
    }

    private static DiskDetail parseNvmeSsdDetail(String device, String fullOutput, List<Integer> historyTemperature,
                                                 List<Long> historyReadBytes, List<Long> historyWriteBytes) {
        long dataUnitsReadBytes = parseNvmeLongValue(fullOutput, NVME_DATA_UNITS_READ) * 512000;
        long dataUnitsWrittenBytes = parseNvmeLongValue(fullOutput, NVME_DATA_UNITS_WRITTEN) * 512000;

        return new DiskDetail(
                device,
                extractDeviceModel(fullOutput),
                extractGroup(fullOutput, SERIAL_NUMBER),
                extractSize(fullOutput),
                parseTemperature(fullOutput),
                extractGroup(fullOutput, HEALTH_STATUS),
                true, // NVMe SSD通常都支持SMART
                true, // NVMe SSD通常都启用SMART
                parseNvmeValue(fullOutput, NVME_POWER_ON_HOURS),
                parseNvmeValue(fullOutput, NVME_POWER_CYCLES),
                dataUnitsReadBytes,
                dataUnitsWrittenBytes,
                0, // HDD特有
                0, // HDD特有
                0, // HDD特有
                0, // HDD特有
                parseNvmeValue(fullOutput, NVME_PERCENTAGE_USED),
                parseNvmeValue(fullOutput, NVME_UNSAFE_SHUTDOWNS),
                parseNvmeValue(fullOutput, NVME_MEDIA_ERRORS),
                0, // SATA SSD特有
                0, // SATA SSD特有
                0, // SATA SSD特有
                0, // SATA SSD特有
                0, // SATA SSD特有
                0, // SATA SSD特有
                0, // SATA SSD特有
                "NVME_SSD",
                historyTemperature,
                historyReadBytes,
                historyWriteBytes
        );
    }

    private static DiskDetail parseSataSsdDetail(String device, String fullOutput, List<Integer> historyTemperature,
                                                 List<Long> historyReadBytes, List<Long> historyWriteBytes) {
        Map<Integer, Map<String, String>> attributesMap = parseSataAttributes(fullOutput);

        // 提取SATA SSD特有的属性
        int ssdLifeLeft = getAttributeValue(attributesMap, ATTR_SSD_LIFE_LEFT, "RAW_VALUE", 0);
        long flashWritesGiB = getAttributeValue(attributesMap, ATTR_FLASH_WRITES_GIB, "RAW_VALUE", 0L);
        long lifetimeWritesGiB = getAttributeValue(attributesMap, ATTR_LIFETIME_WRITES_GIB, "RAW_VALUE", 0L);
        long lifetimeReadsGiB = getAttributeValue(attributesMap, ATTR_LIFETIME_READS_GIB, "RAW_VALUE", 0L);
        int averageEraseCount = getAttributeValue(attributesMap, ATTR_AVERAGE_ERASE_COUNT, "RAW_VALUE", 0);
        int maxEraseCount = getAttributeValue(attributesMap, ATTR_MAX_ERASE_COUNT, "RAW_VALUE", 0);
        int totalEraseCount = getAttributeValue(attributesMap, ATTR_TOTAL_ERASE_COUNT, "RAW_VALUE", 0);
        int unsafeShutdowns = getAttributeValue(attributesMap, ATTR_UNSAFE_SHUTDOWN_COUNT, "RAW_VALUE", 0);
        int mediaErrors = getAttributeValue(attributesMap, ATTR_REPORTED_UNCORRECT, "RAW_VALUE", 0);

        // 提取通用属性
        int powerOnHours = getAttributeValue(attributesMap, 9, "RAW_VALUE", 0);
        int powerCycleCount = getAttributeValue(attributesMap, 12, "RAW_VALUE", 0);
        int temperature = parseTemperature(fullOutput);

        long dataUnitsReadBytes = lifetimeReadsGiB * 1024L * 1024L * 1024L;
        long dataUnitsWrittenBytes = lifetimeWritesGiB * 1024L * 1024L * 1024L;

        return new DiskDetail(
                device,
                extractDeviceModel(fullOutput),
                extractGroup(fullOutput, SERIAL_NUMBER),
                extractSize(fullOutput),
                temperature,
                extractGroup(fullOutput, HEALTH_STATUS),
                isSmartSupported(fullOutput),
                isSmartEnabled(fullOutput),
                powerOnHours,
                powerCycleCount,
                dataUnitsReadBytes,
                dataUnitsWrittenBytes,
                0, // HDD特有
                0, // HDD特有
                0, // HDD特有
                0, // HDD特有
                100 - ssdLifeLeft, // 将SSD剩余寿命转换为已使用百分比
                unsafeShutdowns,
                mediaErrors,
                ssdLifeLeft,
                flashWritesGiB,
                lifetimeWritesGiB,
                lifetimeReadsGiB,
                averageEraseCount,
                maxEraseCount,
                totalEraseCount,
                "SATA_SSD",
                historyTemperature,
                historyReadBytes,
                historyWriteBytes
        );
    }

    /**
     * 从 smartctl 输出中解析累计读写字节数。
     */
    public static long[] parseReadWriteBytes(String fullOutput) {
        String diskType = determineDiskType(fullOutput);
        switch (diskType) {
            case "NVME_SSD":
                long readNvme = parseNvmeLongValue(fullOutput, NVME_DATA_UNITS_READ) * 512000;
                long writeNvme = parseNvmeLongValue(fullOutput, NVME_DATA_UNITS_WRITTEN) * 512000;
                return new long[]{readNvme, writeNvme};
            case "SATA_SSD": {
                Map<Integer, Map<String, String>> attrs = parseSataAttributes(fullOutput);
                long lifetimeReadsGiB = getAttributeValue(attrs, ATTR_LIFETIME_READS_GIB, "RAW_VALUE", 0L);
                long lifetimeWritesGiB = getAttributeValue(attrs, ATTR_LIFETIME_WRITES_GIB, "RAW_VALUE", 0L);
                long readBytes = lifetimeReadsGiB * 1024L * 1024L * 1024L;
                long writeBytes = lifetimeWritesGiB * 1024L * 1024L * 1024L;
                return new long[]{readBytes, writeBytes};
            }
            default: {
                Map<String, Long> attributes = parseAttributes(fullOutput);
                long totalLbasWritten = attributes.getOrDefault("Total_LBAs_Written", 0L);
                long totalLbasRead = attributes.getOrDefault("Total_LBAs_Read", 0L);
                long bytesPerLba = 512L;
                return new long[]{totalLbasRead * bytesPerLba, totalLbasWritten * bytesPerLba};
            }
        }
    }
    
    private static Map<Integer, Map<String, String>> parseSataAttributes(String input) {
        Map<Integer, Map<String, String>> result = new HashMap<>();
        
        // 查找SMART属性数据部分
        int startIndex = input.indexOf("Vendor Specific SMART Attributes with Thresholds:");
        if (startIndex == -1) return result;
        
        // 查找属性表头行
        int headerIndex = input.indexOf("ID#", startIndex);
        if (headerIndex == -1) return result;
        
        // 获取表头行，解析列名
        String headerLine = input.substring(headerIndex, input.indexOf('\n', headerIndex));
        String[] headers = headerLine.split("\\s+");
        
        // 从表头行之后开始解析每一行属性
        String[] lines = input.substring(input.indexOf('\n', headerIndex) + 1).split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                continue; // 跳过非属性行
            }
            
            String[] parts = line.split("\\s+");
            if (parts.length < 10) continue; // 确保有足够的列
            
            try {
                int id = Integer.parseInt(parts[0]);
                Map<String, String> attrs = new HashMap<>();
                
                // 解析每一列的值
                for (int i = 1; i < Math.min(parts.length, headers.length); i++) {
                    attrs.put(headers[i], parts[i]);
                }
                
                // 特殊处理RAW_VALUE，它可能包含额外信息
                if (parts.length >= 10) {
                    attrs.put("RAW_VALUE", parts[9]);
                }
                
                result.put(id, attrs);
            } catch (NumberFormatException e) {
                // 忽略无法解析的行
            }
        }
        
        return result;
    }
    
    private static <T extends Number> T getAttributeValue(Map<Integer, Map<String, String>> attributes, 
                                                      int attributeId, 
                                                      String field, 
                                                      T defaultValue) {
        if (!attributes.containsKey(attributeId) || !attributes.get(attributeId).containsKey(field)) {
            return defaultValue;
        }
        
        String value = attributes.get(attributeId).get(field);
        // 处理可能包含额外信息的值，例如 "100 (Min/Max 20/76)"
        if (value.contains(" ")) {
            value = value.substring(0, value.indexOf(' '));
        }
        
        try {
            if (defaultValue instanceof Integer) {
                return (T) Integer.valueOf(value);
            } else if (defaultValue instanceof Long) {
                return (T) Long.valueOf(value);
            } else if (defaultValue instanceof Float) {
                return (T) Float.valueOf(value);
            } else if (defaultValue instanceof Double) {
                return (T) Double.valueOf(value);
            }
        } catch (NumberFormatException e) {
            // 忽略解析错误
        }
        
        return defaultValue;
    }
    
    private static String extractDeviceModel(String input) {
        Matcher matcher = DEVICE_MODEL.matcher(input);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return "N/A";
    }

    private static String extractGroup(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : "N/A";
    }

    private static String extractSize(String input) {
        Matcher matcher = USER_CAPACITY.matcher(input);
        if (matcher.find()) {
            // 处理不同格式的容量信息
            if (matcher.group(2) != null) {
                return matcher.group(2);
            } else if (matcher.group(4) != null) {
                return matcher.group(4);
            }
        }
        return "N/A";
    }

    private static String parseHealthStatus(String healthOutput) {
        Matcher matcher = HEALTH_STATUS.matcher(healthOutput);
        return matcher.find() ? matcher.group(1) : "UNKNOWN";
    }

    public static int parseTemperature(String dataOutput) {
        Matcher matcher = TEMPERATURE.matcher(dataOutput);
        try {
            if (matcher.find()) {
                // 尝试获取第一个捕获组（HDD格式）
                String temp = matcher.group(1);
                if (temp == null || temp.isEmpty()) {
                    // 如果第一个捕获组为空，尝试获取第二个捕获组（NVMe格式）
                    temp = matcher.group(2);
                    if (temp == null || temp.isEmpty()) {
                        // 如果第二个捕获组也为空，尝试获取第三个捕获组（SATA SSD格式）
                        temp = matcher.group(3);
                    }
                }
                return Integer.parseInt(temp);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return 0;
    }

    private static boolean isSmartSupported(String input) {
        return input.contains("SMART support is: Available");
    }

    private static boolean isSmartEnabled(String input) {
        return input.contains("SMART support is: Enabled");
    }

    private static Map<String, Long> parseAttributes(String input) {
        Map<String, Long> attributes = new HashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(input);

        while (matcher.find()) {
            String attributeName = matcher.group(2).trim();
            String rawValueStr = matcher.group(3).trim();

            // 去掉括号内的说明内容，例如 "273 (Average 406)" -> "273"
            rawValueStr = rawValueStr.replaceAll("\\(.*?\\)", "").trim();

            try {
                long rawValue = Long.parseLong(rawValueStr);
                attributes.put(attributeName, rawValue); // 限制为int范围
            } catch (NumberFormatException ignored) {
                // 忽略无法解析的数值
            }
        }
        return attributes;
    }
    
    private static int parseNvmeValue(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1).replaceAll(",", ""));
            } catch (NumberFormatException e) {
                // 忽略解析错误
            }
        }
        return 0;
    }
    
    private static long parseNvmeLongValue(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1).replaceAll(",", ""));
            } catch (NumberFormatException e) {
                // 忽略解析错误
            }
        }
        return 0;
    }
}
