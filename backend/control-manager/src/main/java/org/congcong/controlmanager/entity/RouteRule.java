package org.congcong.controlmanager.entity;

import lombok.Data;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.RouteConditionType;

/**
 * 路由规则：定义匹配条件和转发动作
 */
@Data
public class RouteRule {

    /**
     * 支持属于，不属于某个域名或者地理位置
     */
    private RouteConditionType domain;

    private MatchOp geo;

    private String value;
}