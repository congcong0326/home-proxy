package org.congcong.common.dto;

import lombok.Data;

@Data
public class ProxyTimeContext {

    /**
     * 请求开始与结束时间
     */
    private long requestStartTime = System.currentTimeMillis();
    private long requestEndTime;

    /**
     * DNS 解析耗时
     */
    private long dnsStartTime;
    private long dnsEndTime;

    /**
     * 代理与客户端完成握手（CONNECT/SOCKS）的耗时
     */
    private long connectStartTime = System.currentTimeMillis();
    private long connectEndTime;

    /**
     * 与目标服务器建立耗时
     */
    private long connectTargetStartTime;
    private long connectTargetEndTime;


    /**
     * 计算通用耗时：若结束时间不大于开始时间，返回 0
     */
    private long calcDuration(long start, long end) {
        return end > start ? (end - start) : 0L;
    }

    /**
     * 整体请求耗时（ms）
     */
    public long getRequestDuration() {
        return calcDuration(requestStartTime, requestEndTime);
    }

    /**
     * DNS 解析耗时（ms）
     */
    public long getDnsDuration() {
        return calcDuration(dnsStartTime, dnsEndTime);
    }

    /**
     * 与客户端握手（CONNECT/SOCKS）耗时（ms）
     */
    public long getConnectDuration() {
        return calcDuration(connectStartTime, connectEndTime);
    }

    /**
     * 与目标服务器建立连接耗时（ms）
     */
    public long getConnectTargetDuration() {
        return calcDuration(connectTargetStartTime, connectTargetEndTime);
    }

}
