package org.congcong.proxyworker.protocol.transparent;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@ChannelHandler.Sharable
@Slf4j
public  class TransparentChecker extends ChannelInboundHandlerAdapter {

    public static TransparentChecker getInstance() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final TransparentChecker INSTANCE = new TransparentChecker();
    }

    // 本机 IP 缓存（含 WAN/LAN/loopback/vpn 等），避免每次 channelActive 都枚举网卡
    private static volatile Set<String> LOCAL_IPS = Collections.emptySet();
    private static volatile long LAST_REFRESH_NANOS = 0L;
    private static final long REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60 * 10);

    private TransparentChecker() {}

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(ctx.channel());

        if (proxyContext == null || inboundConfig == null) {
            log.warn("ProxyContext or InboundConfig is null.");
            ctx.fireChannelActive();
            return;
        }

        String originalTargetIP = normalizeIp(proxyContext.getOriginalTargetIP());
        Integer originalTargetPort = proxyContext.getOriginalTargetPort();
        Integer listenPort = inboundConfig.getPort();

        // listenPort / originalTargetPort 不完整，直接放行（避免误杀）
        if (listenPort == null || listenPort <= 0 || originalTargetPort == null || originalTargetPort <= 0) {
            log.warn("Listen port or original target port is null.");
            ctx.fireChannelActive();
            return;
        }

        // 关键：原始目的端口 == 入口端口，且原始目的 IP 是本机地址 => 高概率自连/闭环
        if (originalTargetPort.intValue() == listenPort.intValue() && isLocalIp(originalTargetIP)) {
            SocketAddress remote = ctx.channel().remoteAddress();
            String remoteStr = format(remote);
            log.warn("Transparent loop/self-connect detected. reject. remote={}, originalDst={}:{}, listenPort={}",
                    remoteStr, originalTargetIP, originalTargetPort, listenPort);

            // 直接断开，避免连接风暴
            ctx.close();
            return;
        }

        ctx.fireChannelActive();
    }

    private static boolean isLocalIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }

        // 这些本质都是“本机”
        if ("0.0.0.0".equals(ip) || "::".equals(ip) || "127.0.0.1".equals(ip) || "::1".equals(ip)) {
            return true;
        }

        // 刷新本机 IP 集合（应对 PPPoE / VPN 变化）
        refreshLocalIpsIfNeeded();

        // 先走字符串集合（最快）
        if (LOCAL_IPS.contains(ip)) {
            return true;
        }

        // 再做 InetAddress 语义判断（覆盖一些边界形态）
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isAnyLocalAddress()
                    || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || LOCAL_IPS.contains(addr.getHostAddress());
        } catch (Exception ignore) {
            // 解析失败就当非本机，避免误伤
            return false;
        }
    }

    private static void refreshLocalIpsIfNeeded() {
        long now = System.nanoTime();
        if (now - LAST_REFRESH_NANOS < REFRESH_INTERVAL_NANOS && !LOCAL_IPS.isEmpty()) {
            return;
        }
        synchronized (TransparentChecker.class) {
            now = System.nanoTime();
            if (now - LAST_REFRESH_NANOS < REFRESH_INTERVAL_NANOS && !LOCAL_IPS.isEmpty()) {
                return;
            }
            Set<String> ips = new HashSet<>();
            try {
                Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                while (nis.hasMoreElements()) {
                    NetworkInterface ni = nis.nextElement();
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        ips.add(a.getHostAddress());
                    }
                }
            } catch (Exception e) {
                // 枚举失败也不致命，保留旧值
                log.debug("refreshLocalIps failed", e);
            }

            // 保底把 loopback / any 加进去
            ips.add("127.0.0.1");
            ips.add("::1");
            ips.add("0.0.0.0");
            ips.add("::");
            log.info("refreshLocalIps success, ips={}", ips);
            LOCAL_IPS = Collections.unmodifiableSet(ips);
            LAST_REFRESH_NANOS = now;
        }
    }

    private static String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        String s = ip.trim();
        if (s.isEmpty()) {
            return s;
        }
        // 去掉可能的 []（有些地方会带）
        if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        // 处理 IPv6-mapped IPv4：::ffff:112.193.57.159
        if (s.startsWith("::ffff:")) {
            s = s.substring("::ffff:".length());
        }
        return s;
    }

    private static String format(SocketAddress addr) {
        if (addr == null) {
            return "null";
        }
        if (addr instanceof InetSocketAddress) {
            InetSocketAddress isa = (InetSocketAddress) addr;
            String host = isa.getAddress() != null ? isa.getAddress().getHostAddress() : String.valueOf(isa.getHostString());
            return host + ":" + isa.getPort();
        }
        return addr.toString();
    }
}
