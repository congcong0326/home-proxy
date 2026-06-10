package org.congcong.controlmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.WolWakeTaskPayload;
import org.congcong.controlmanager.config.WolConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class WolService {

    private static final String LIMITED_BROADCAST_ADDRESS = "255.255.255.255";

    private final WolConfigService wolConfigService;
    private final WorkerControlService workerControlService;

    @Autowired
    public WolService(WolConfigService wolConfigService, WorkerControlService workerControlService) {
        this.wolConfigService = wolConfigService;
        this.workerControlService = workerControlService;
    }

    /**
     * 根据设备ID发送WOL魔术包
     */
    public String sendWolPacketById(Long configId) {
        WolConfig config = wolConfigService.getConfigById(configId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WOL配置不存在: " + configId));
        
        if (!config.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备已禁用: " + config.getName());
        }
        
        return sendWolPacket(config);
    }

    /**
     * 根据IP地址发送WOL魔术包
     */
    public String sendWolPacketByIp(String ipAddress) {
        WolConfig config = wolConfigService.getConfigByIpAddress(ipAddress)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到IP地址对应的WOL配置: " + ipAddress));
        
        if (!config.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备已禁用: " + config.getName());
        }
        
        return sendWolPacket(config);
    }

    /**
     * 根据设备名称发送WOL魔术包
     */
    public String sendWolPacketByName(String deviceName) {
        WolConfig config = wolConfigService.getConfigByName(deviceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到设备名称对应的WOL配置: " + deviceName));
        
        if (!config.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备已禁用: " + config.getName());
        }
        
        return sendWolPacket(config);
    }

    /**
     * 发送WOL魔术包（原有方法，保持兼容性）
     */
    public String sendWolPacket(String macAddress, String broadcastIp, int port) {
        return sendWolPacket(macAddress, broadcastIp, port, "未知设备");
    }

    private String sendWolPacket(WolConfig config) {
        String broadcastIp = resolveBroadcastAddress(config.getIpAddress(), config.getSubnetMask());
        log.debug("解析WOL广播地址: 设备IP={}, 子网掩码={}, 广播地址={}",
                config.getIpAddress(), config.getSubnetMask(), broadcastIp);
        return sendWolPacket(config.getMacAddress(), broadcastIp, config.getWolPort(), config.getName());
    }

    /**
     * 发送WOL魔术包的核心实现
     */
    private String sendWolPacket(String macAddress, String broadcastIp, int port, String deviceName) {
        try {
            log.info("准备派发WOL唤醒任务到设备: {} (MAC: {}, 广播地址: {}, 端口: {})",
                    deviceName, macAddress, broadcastIp, port);

            WolWakeTaskPayload payload = new WolWakeTaskPayload(deviceName, macAddress, broadcastIp, port);
            workerControlService.createTask(WorkerControlService.TASK_TYPE_WOL_WAKE, payload);

            log.info("WOL唤醒任务已派发到设备: {} (MAC: {})", deviceName, macAddress);
            return String.format("WOL唤醒请求已提交: %s", deviceName);

        } catch (RuntimeException e) {
            String result = String.format("提交WOL唤醒请求失败，设备 %s: %s", deviceName, e.getMessage());
            log.error("派发WOL唤醒任务到设备 {} 失败", deviceName, e);
            throw new RuntimeException(result, e);
        }
    }

    static String resolveBroadcastAddress(String ipAddress, String subnetMask) {
        String normalizedMask = subnetMask == null ? "" : subnetMask.trim();
        if (normalizedMask.isEmpty() || LIMITED_BROADCAST_ADDRESS.equals(normalizedMask)) {
            return LIMITED_BROADCAST_ADDRESS;
        }

        int mask = ipv4ToInt(normalizedMask);
        if (!isContiguousSubnetMask(mask)) {
            return normalizedMask;
        }

        int ip = ipv4ToInt(ipAddress);
        return intToIpv4(ip | ~mask);
    }

    private static boolean isContiguousSubnetMask(int mask) {
        int invertedMask = ~mask;
        return (invertedMask & (invertedMask + 1)) == 0;
    }

    private static int ipv4ToInt(String value) {
        String[] octets = value.trim().split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("无效的IPv4地址: " + value);
        }

        int result = 0;
        for (String octet : octets) {
            int parsed = Integer.parseInt(octet);
            if (parsed < 0 || parsed > 255) {
                throw new IllegalArgumentException("无效的IPv4地址: " + value);
            }
            result = (result << 8) | parsed;
        }
        return result;
    }

    private static String intToIpv4(int value) {
        return String.format("%d.%d.%d.%d",
                (value >>> 24) & 0xFF,
                (value >>> 16) & 0xFF,
                (value >>> 8) & 0xFF,
                value & 0xFF);
    }

}
