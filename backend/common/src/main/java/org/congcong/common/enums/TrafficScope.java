package org.congcong.common.enums;

/**
 * 流量范围筛选。
 *
 * INTERNAL / EXTERNAL 按访问日志中的改写目标地址划分：只有私有或本地 IP
 * 视为内网，域名、空值和其他 IP 均视为外网。
 */
public enum TrafficScope {
    ALL,
    INTERNAL,
    EXTERNAL
}
