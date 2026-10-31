package org.congcong.controlmanager.dto.route;

import lombok.Data;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.enums.OutboundKind;
import org.congcong.controlmanager.enums.RouteAction;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理台路由视图。worker 下发契约仍是 common 的 {@code RouteDTO}，两者不共用。
 */
@Data
public class RouteView {
    private Long id;
    private String name;
    private List<RouteRule> rules;
    private RouteAction action;
    private Long outboundId;
    private String outboundName;
    private OutboundKind outboundKind;
    private Long rewriteTableId;
    private String rewriteTableName;
    private Long rewriteEntryCount;
    private String rewriteTargetHost;
    private Integer rewriteTargetPort;
    /** 编译出的下发策略，只读。 */
    private RoutePolicy policy;
    private String outboundTag;
    private Integer status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
