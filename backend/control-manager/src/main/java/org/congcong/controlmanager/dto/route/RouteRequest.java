package org.congcong.controlmanager.dto.route;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.congcong.common.dto.RouteRule;
import org.congcong.controlmanager.enums.RouteAction;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建／更新路由请求。字段组合按动作校验，见 docs/development/route-outbound-split-phase1-design.md 第 3.3 节。
 */
@Data
public class RouteRequest {

    @NotBlank(message = "路由名称不能为空")
    @Size(max = 64, message = "路由名称长度不能超过64个字符")
    private String name;

    /** 引用改写表时必须为空，其余动作必填。 */
    private List<RouteRule> rules = new ArrayList<>();

    @NotNull(message = "路由动作不能为空")
    private RouteAction action;

    /** FORWARD 必填；改写动作忽略。 */
    private Long outboundId;

    /** REWRITE_DESTINATION 可选，与固定目标二选一。 */
    private Long rewriteTableId;

    @Size(max = 255, message = "改写目标长度不能超过255个字符")
    private String rewriteTargetHost;

    private Integer rewriteTargetPort;

    private Integer status;

    @Size(max = 255, message = "备注长度不能超过255个字符")
    private String notes;
}
