package org.congcong.proxyworker.audit;

import io.netty.channel.Channel;
import io.netty.handler.codec.dns.DnsResponseCode;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.dto.AccessLog;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.audit.impl.AsyncHttpLogPublisher;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class AccessLogUtil {

    private static final LogPublisher logPublisher = new AsyncHttpLogPublisher(1000, 100, 50);
    
    // 高性能ID生成器
    private static final AtomicLong REQUEST_ID_COUNTER = new AtomicLong(0);
    private static final long START_TIMESTAMP = System.currentTimeMillis();

    public static void logSuccess(Channel channel) {
        // 从 channel 获取 ProxyContext 和 ProxyTimeContext
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(channel);
        ProxyTimeContext timeContext = ChannelAttributes.getProxyTimeContext(channel);
        
        if (proxyContext == null || timeContext == null) {
            return; // 如果上下文为空，直接返回
        }
        RoutePolicy routePolicy = proxyContext.getRoutePolicy();
        // 太多无用的广告屏蔽策略
        if (routePolicy == RoutePolicy.BLOCK) {
            return;
        }
        // 设置请求结束时间
        timeContext.setRequestEndTime(System.currentTimeMillis());
        
        // 填充 AccessLog 并发送
        AccessLog accessLog = createAccessLog(proxyContext, timeContext);
        accessLog.setStatus(200); // 成功状态码
        
        logPublisher.publishAccess(accessLog);
    }

    public static void logFailure(Channel channel, int status, String errorCode, String errorMsg) {
        // 从 channel 获取 ProxyContext 和 ProxyTimeContext
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(channel);
        ProxyTimeContext timeContext = ChannelAttributes.getProxyTimeContext(channel);
        
        if (proxyContext == null || timeContext == null) {
            return; // 如果上下文为空，直接返回
        }
        if (proxyContext.getRoutePolicy() == RoutePolicy.BLOCK) {
            return;
        }
        
        // 设置请求结束时间
        timeContext.setRequestEndTime(System.currentTimeMillis());
        
        // 填充 AccessLog 并设置错误信息
        AccessLog accessLog = createAccessLog(proxyContext, timeContext);
        accessLog.setStatus(status);
        accessLog.setErrorCode(errorCode);
        accessLog.setErrorMsg(errorMsg);
        
        logPublisher.publishAccess(accessLog);
    }

    public static void logDns(ProxyContext proxyContext,
                              ProxyTimeContext timeContext,
                              DnsProxyContext dnsCtx,
                              DnsResponseCode code,
                              List<String> answerIps) {
        if (proxyContext == null) return;
        ProxyTimeContext ctx = timeContext != null ? timeContext : new ProxyTimeContext();
        ctx.setRequestEndTime(System.currentTimeMillis());

        AccessLog accessLog = createAccessLog(proxyContext, ctx);
        accessLog.setOriginalTargetHost(dnsCtx.getQName());
        accessLog.setOriginalTargetPort(53);
        boolean ok = code == DnsResponseCode.NOERROR;
        accessLog.setStatus(ok ? 200 : 502);
        accessLog.setDnsAnswerIps(answerIps == null ? Collections.emptyList() : answerIps);
        if (!ok) {
            accessLog.setErrorCode(code.toString());
            accessLog.setErrorMsg("DNS response code: " + code);
        }

        logPublisher.publishAccess(accessLog);
    }

    /**
     * 高性能生成唯一请求ID
     * 格式: {启动时间戳}-{当前时间戳}-{线程ID}-{递增序号}
     * 这种方式比UUID性能高很多，同时保证唯一性
     */
    private static String generateRequestId() {
        long currentTime = System.currentTimeMillis();
        long threadId = Thread.currentThread().getId();
        long sequence = REQUEST_ID_COUNTER.incrementAndGet();
        
        return String.format("%d-%d-%d-%d", START_TIMESTAMP, currentTime, threadId, sequence);
    }

    /**
     * 从 ProxyContext 和 ProxyTimeContext 创建 AccessLog
     */
    private static AccessLog createAccessLog(ProxyContext proxyContext, ProxyTimeContext timeContext) {
        AccessLog accessLog = new AccessLog();
        
        // 生成高性能请求ID用于追踪
        accessLog.setRequestId(generateRequestId());
        
        // 用户与代理信息
        accessLog.setUserId(proxyContext.getUserId());
        accessLog.setUsername(proxyContext.getUserName());
        accessLog.setProxyName(proxyContext.getProxyName());
        accessLog.setInboundId(proxyContext.getProxyId());
        
        // 源地址信息
        accessLog.setClientIp(proxyContext.getClientIp());
        accessLog.setClientPort(proxyContext.getClientPort());
        accessLog.setSrcGeoCountry(proxyContext.getSrcGeoCountry());
        accessLog.setSrcGeoCity(proxyContext.getSrcGeoCity());
        
        // 目标地址信息（原始与改写）
        accessLog.setOriginalTargetHost(proxyContext.getOriginalTargetHost());
        accessLog.setOriginalTargetIP(proxyContext.getOriginalTargetIP() == null ? proxyContext.getRealTargetIp() : proxyContext.getOriginalTargetIP());
        accessLog.setOriginalTargetPort(proxyContext.getOriginalTargetPort());
        accessLog.setRewriteTargetHost(proxyContext.getRewriteTargetHost());
        accessLog.setRewriteTargetPort(proxyContext.getRewriteTargetPort());
        accessLog.setDstGeoCountry(proxyContext.getDstGeoCountry());
        accessLog.setDstGeoCity(proxyContext.getDstGeoCity());
        
        // 协议与路由信息
        accessLog.setInboundProtocolType(proxyContext.getInboundProtocolType() != null ? 
            proxyContext.getInboundProtocolType().getValue() : null);
        accessLog.setOutboundProtocolType(proxyContext.getOutboundProtocolType() != null ? 
            proxyContext.getOutboundProtocolType().getValue() : ProtocolType.NONE.getValue());
        accessLog.setRoutePolicyName(proxyContext.getRoutePolicyName());
        accessLog.setRoutePolicyId(proxyContext.getRoutePolicyId());
        
        // 流量统计
        accessLog.setBytesIn(proxyContext.getBytesIn());
        accessLog.setBytesOut(proxyContext.getBytesOut());
        
        // 时延信息
        accessLog.setRequestDurationMs(timeContext.getRequestDuration());
        accessLog.setDnsDurationMs(timeContext.getDnsDuration());
        accessLog.setConnectDurationMs(timeContext.getConnectDuration());
        accessLog.setConnectTargetDurationMs(timeContext.getConnectTargetDuration());
        
        return accessLog;
    }

    public static void start() {
        // 触发类加载就可以初始化日志服务
        // logPublisher 已经在静态初始化时创建，这里可以执行额外的启动逻辑
        // 例如：验证配置、预热连接池等
    }

    public static void stop() {
        logPublisher.shutdown();
    }
}
