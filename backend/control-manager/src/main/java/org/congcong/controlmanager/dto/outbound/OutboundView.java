package org.congcong.controlmanager.dto.outbound;

import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.controlmanager.enums.OutboundKind;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class OutboundView {
    private Long id;
    private String name;
    private OutboundKind kind;
    private boolean builtin;
    private ProtocolType proxyType;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private ProxyEncAlgo encAlgo;
    private Map<String, Object> config;
    private String notes;
    /** 引用该出站的路由数量。 */
    private long routeCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
