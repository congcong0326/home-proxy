package org.congcong.controlmanager.enums;

/**
 * 出站类别。
 */
public enum OutboundKind {
    /** 内置直连。 */
    DIRECT,
    /** 内置阻断。 */
    BLOCK,
    /** 上游代理：HTTPS_CONNECT / SOCKS5 / SHADOW_SOCKS / VLESS_REALITY。 */
    PROXY,
    /** DNS 上游：DNS_SERVER / DOT。 */
    DNS_UPSTREAM
}
