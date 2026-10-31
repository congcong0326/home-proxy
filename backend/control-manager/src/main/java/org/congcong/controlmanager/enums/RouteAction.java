package org.congcong.controlmanager.enums;

/**
 * 路由动作。worker 不感知该枚举，下发前由 {@code RouteCompiler} 编译为 {@code RoutePolicy}。
 */
public enum RouteAction {
    /** 转发到引用的出站。 */
    FORWARD,
    /** 改写连接目标后直连，目标来自改写表或固定目标。 */
    REWRITE_DESTINATION,
    /** DNS 应答改写为固定 IP。 */
    REWRITE_DNS
}
