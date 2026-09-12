package org.congcong.controlmanager.logstore;

import org.congcong.common.enums.TrafficScope;

/**
 * ClickHouse access_log 的流量范围条件。
 *
 * 只根据 rewrite_target_host 是否为私有或本地 IP 进行分类；非 IP 的域名和
 * 空字符串不匹配该条件，因此归入外网流量。
 */
public final class TrafficScopeSql {
    /**
     * {@code isIPAddressInRange} 会对 String 参数直接解析，空字符串或域名会使整条
     * 查询失败。先使用 OrNull 解析函数将地址规范化；无法解析的值替换成公网地址，
     * 使其稳定地归入外网流量。
     */
    private static final String SAFE_REWRITE_TARGET_HOST =
            "coalesce(IPv4NumToString(IPv4StringToNumOrNull(rewrite_target_host)), " +
            "IPv6NumToString(IPv6StringToNumOrNull(rewrite_target_host)), '0.0.0.0')";

    private static final String INTERNAL_REWRITE_TARGET_PREDICATE = "(" +
            "isIPAddressInRange(" + SAFE_REWRITE_TARGET_HOST + ", '10.0.0.0/8') OR " +
            "isIPAddressInRange(" + SAFE_REWRITE_TARGET_HOST + ", '172.16.0.0/12') OR " +
            "isIPAddressInRange(" + SAFE_REWRITE_TARGET_HOST + ", '192.168.0.0/16') OR " +
            "isIPAddressInRange(" + SAFE_REWRITE_TARGET_HOST + ", '127.0.0.0/8') OR " +
            "isIPAddressInRange(" + SAFE_REWRITE_TARGET_HOST + ", '169.254.0.0/16') OR " +
            "isIPAddressInRange(" + SAFE_REWRITE_TARGET_HOST + ", 'fc00::/7') OR " +
            "isIPAddressInRange(" + SAFE_REWRITE_TARGET_HOST + ", 'fe80::/10') OR " +
            "isIPAddressInRange(" + SAFE_REWRITE_TARGET_HOST + ", '::1/128')" +
            ")";

    private TrafficScopeSql() {
    }

    public static void appendFilter(StringBuilder sql, TrafficScope scope) {
        if (scope == TrafficScope.INTERNAL) {
            sql.append(" AND ").append(INTERNAL_REWRITE_TARGET_PREDICATE);
        } else if (scope == TrafficScope.EXTERNAL) {
            sql.append(" AND NOT ").append(INTERNAL_REWRITE_TARGET_PREDICATE);
        }
    }
}
