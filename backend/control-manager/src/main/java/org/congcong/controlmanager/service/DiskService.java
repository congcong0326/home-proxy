package org.congcong.controlmanager.service;


import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.entity.disk.DiskDetail;
import org.congcong.controlmanager.entity.disk.DiskInfo;
import org.congcong.controlmanager.entity.disk.DiskIoStats;
import org.springframework.stereotype.Service;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DiskService {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, List<Integer>> temperatureInfo = new HashMap<>();
    private final Map<String, List<Long>> readDeltaInfo = new HashMap<>();
    private final Map<String, List<Long>> writeDeltaInfo = new HashMap<>();
    private final Map<String, LocalDate> lastUpdateDate = new HashMap<>();
    private final Map<String, Long> lastReadBytes = new HashMap<>();
    private final Map<String, Long> lastWriteBytes = new HashMap<>();
    private final List<String> devices = new ArrayList<>();
    private boolean isSupport = false;

    @PostConstruct
    public void start() {
        isSupport = isSupport();
        if (!isSupport) {
            return;
        }
        devices.addAll(getPhysicalDisks());
        scheduler.scheduleAtFixedRate(() -> {
            LocalDate nowDate = LocalDate.now();
            int currentSlot = LocalTime.now().getHour() * 6 + LocalTime.now().getMinute() / 10;

            for (String device : devices) {
                try {
                    Sample sample = sampleDevice(device);

                    List<Integer> temps = temperatureInfo.computeIfAbsent(device, k -> new ArrayList<>());
                    List<Long> reads = readDeltaInfo.computeIfAbsent(device, k -> new ArrayList<>());
                    List<Long> writes = writeDeltaInfo.computeIfAbsent(device, k -> new ArrayList<>());
                    LocalDate lastDate = lastUpdateDate.get(device);

                    // 如果是新的一天，重置list（保持 lastRead/lastWrite 以便计算差值）
                    if (lastDate == null || !lastDate.equals(nowDate)) {
                        temps.clear();
                        reads.clear();
                        writes.clear();
                        lastUpdateDate.put(device, nowDate);
                    }

                    fillToSlot(temps, currentSlot, 0);
                    fillToSlot(reads, currentSlot, 0L);
                    fillToSlot(writes, currentSlot, 0L);

                    temps.set(currentSlot, sample.temperature());

                    long readDelta = computeDelta(lastReadBytes, device, sample.readBytes());
                    long writeDelta = computeDelta(lastWriteBytes, device, sample.writeBytes());
                    reads.set(currentSlot, readDelta);
                    writes.set(currentSlot, writeDelta);
                } catch (Exception e) {
                    log.error("获取磁盘指标失败", e);
                }
            }
        }, 0, 10, TimeUnit.MINUTES);
    }

    private int getTemperature(String device) {
        String dataOutput = executeSmartCtl(device, "-A");
        return SmartCtlParser.parseTemperature(dataOutput);
    }

    private Sample sampleDevice(String device) {
        String output = executeSmartCtl(device, "-a");
        int temperature = SmartCtlParser.parseTemperature(output);
        long[] rw = SmartCtlParser.parseReadWriteBytes(output);
        return new Sample(temperature, rw[0], rw[1]);
    }

    private void fillToSlot(List<Integer> list, int slot, Integer defaultVal) {
        while (list.size() <= slot) {
            list.add(defaultVal);
        }
    }

    private void fillToSlot(List<Long> list, int slot, Long defaultVal) {
        while (list.size() <= slot) {
            list.add(defaultVal);
        }
    }

    private long computeDelta(Map<String, Long> lastMap, String device, long current) {
        Long last = lastMap.get(device);
        long delta = (last == null) ? 0L : Math.max(0L, current - last);
        lastMap.put(device, current);
        return delta;
    }

    private record Sample(int temperature, long readBytes, long writeBytes) {}

    public List<DiskInfo> getAllDisks() {
        if (!isSupport) {
            return Collections.emptyList();
        }
        return devices.stream()
            .map(this::getDiskInfo)
            .toList();
    }

    public List<DiskIoStats> getDailyIoStats() {
        List<DiskIoStats> list = new ArrayList<>();
        for (String device : devices) {
            List<Long> reads = readDeltaInfo.getOrDefault(device, Collections.emptyList());
            List<Long> writes = writeDeltaInfo.getOrDefault(device, Collections.emptyList());
            long totalRead = reads.stream().mapToLong(Long::longValue).sum();
            long totalWrite = writes.stream().mapToLong(Long::longValue).sum();
            list.add(new DiskIoStats(
                    device,
                    new ArrayList<>(reads),
                    new ArrayList<>(writes),
                    totalRead,
                    totalWrite
            ));
        }
        return list;
    }



    public DiskDetail getDiskDetail(String device) {
        if (!devices.contains(device)) {
            throw new UnsupportedOperationException("Device not found");
        }
        return SmartCtlParser.parseDetail(
                device,
                executeSmartCtl(device, "-a"),
                temperatureInfo.get(device),
                readDeltaInfo.get(device),
                writeDeltaInfo.get(device)
        );
    }

    private DiskInfo getDiskInfo(String device) {
        String infoOutput = executeSmartCtl(device, "-i");
        String healthOutput = executeSmartCtl(device, "-H");
        String dataOutput = executeSmartCtl(device, "-A");
        return SmartCtlParser.parseBasicInfo(device, infoOutput, healthOutput, dataOutput);
    }


    private String executeSmartCtl(String device, String option) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/usr/sbin/smartctl", option, "/dev/" + device);
            pb.redirectErrorStream(true);  // 合并 stderr
            Process process = pb.start();

            // 自动关闭 BufferedReader，避免资源泄露
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                boolean success = process.waitFor(5, TimeUnit.SECONDS);
                if (!success || process.exitValue() != 0) {
                    throw new RuntimeException("smartctl exited with code: " + process.exitValue()
                            + "\nOutput:\n" + output);
                }

                return output.toString();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Smartctl execution failed", e);
        }
    }



    private static List<String> getPhysicalDisks() {
        List<String> physicalDisks = new ArrayList<>();

        try {
            Process process = new ProcessBuilder("lsblk", "-o", "NAME,TYPE,SIZE,MODEL").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            boolean headerSkipped = false;

            while ((line = reader.readLine()) != null) {
                if (!headerSkipped) {
                    // 跳过标题行
                    headerSkipped = true;
                    continue;
                }

                // 去掉行首空格并按空白分割
                String[] parts = line.trim().split("\\s+");

                // 合法的硬盘行一般会有 4 个字段：NAME TYPE SIZE MODEL
                if (parts.length >= 4) {
                    String name = parts[0];
                    String type = parts[1];
                    String model = parts[3];

                    if ("disk".equals(type) && !model.equals("") && !model.equalsIgnoreCase("null")) {
                        physicalDisks.add(name);
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return physicalDisks;
    }


    private static boolean isLinux() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("linux");
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder("which", command).start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSupport() {
        if (!isLinux()) {
            log.warn("not linux, can not support disk monitor");
            return false;
        }

        boolean smartctlExists = isCommandAvailable("smartctl");
        boolean lsblkExists = isCommandAvailable("lsblk");

        if (smartctlExists && lsblkExists) {
            return true;
        } else {
            if (!smartctlExists) log.warn("need - smartctl");
            if (!lsblkExists) log.warn("need - lsblk");
            return false;
        }
    }

}
