package org.congcong.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class InboundRouteBinding {

    /**
     * 绑定到同一路由序列的用户ID列表
     */
    private List<Long> userIds;

    /**
     * 需要按顺序遍历的路由策略ID列表
     */
    private List<Long> routeIds;

}
