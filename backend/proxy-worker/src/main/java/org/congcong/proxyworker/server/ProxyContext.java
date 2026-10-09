package org.congcong.proxyworker.server;

import com.google.common.net.InetAddresses;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.server.factory.ProxyServerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ProxyContext {


    private final Map<Long, AbstractProxyServer> servers = new HashMap<>();
    private final Map<Long, InboundConfig> configs = new HashMap<>();
    // Publish the DNS view separately from the lifecycle maps. Request event loops
    // must not acquire this monitor: closing a server waits for those loops to exit.
    // Runtime InboundConfig snapshots are replaced, rather than modified in place.
    private volatile Optional<InboundConfig> enabledDnsInbound = Optional.empty();
    private final AtomicInteger activeConnectionCount = new AtomicInteger(0);

    private ProxyContext() {}

    private static class Holder {
        private static final ProxyContext INSTANCE = new ProxyContext();
    }

    public static ProxyContext getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized ConfigApplyResult refresh(List<InboundConfig> inboundConfigs) {
        try {
            validate(inboundConfigs);
        } catch (IllegalArgumentException error) {
            return ConfigApplyResult.failure(null, error);
        }
        List<InboundConfig> previous = new ArrayList<>(configs.values());
        java.util.concurrent.atomic.AtomicReference<Long> activeInboundId = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            apply(inboundConfigs, activeInboundId::set);
            return ConfigApplyResult.applied();
        } catch (Exception error) {
            log.error("代理配置应用失败，正在恢复上一份运行配置", error);
            closeAll();
            try {
                apply(previous, ignored -> { });
            } catch (Exception rollbackError) {
                log.error("代理配置回滚失败", rollbackError);
                error.addSuppressed(rollbackError);
            }
            return ConfigApplyResult.failure(activeInboundId.get(), error);
        }
    }

    private void apply(List<InboundConfig> inboundConfigs, java.util.function.Consumer<Long> activeInboundConsumer) throws Exception {
        Map<Long, InboundConfig> newConfigs = new HashMap<>();
        if (inboundConfigs != null) {
            for (InboundConfig cfg : inboundConfigs) {
                if (cfg != null && cfg.getId() != null) {
                    newConfigs.put(cfg.getId(), cfg);
                }
            }
        }

        InboundConfig dnsSnapshot = newConfigs.values().stream()
                .filter(config -> config.getProtocol() == ProtocolType.DNS_SERVER).findFirst().orElse(null);

        // Quiesce Fake DNS before touching the transparent listener. DNS is
        // restarted last, after the transparent listener has bound successfully.
        for (Map.Entry<Long, AbstractProxyServer> entry : servers.entrySet()) {
            InboundConfig current = configs.get(entry.getKey());
            if (current != null && current.getProtocol() == ProtocolType.DNS_SERVER
                    && Boolean.TRUE.equals(current.getFakeIpEnabled()) && entry.getValue().isRunning()) {
                entry.getValue().close();
            }
        }

        // 1) 关闭并移除下线的服务
        for (Iterator<Map.Entry<Long, AbstractProxyServer>> it = servers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, AbstractProxyServer> entry = it.next();
            Long id = entry.getKey();
            if (!newConfigs.containsKey(id)) {
                AbstractProxyServer server = entry.getValue();
                try {
                    server.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                it.remove();
                configs.remove(id);
            }
        }

        // 2) 更新变更的服务（关闭旧的，启动新的）与新增的服务
        List<Map.Entry<Long, InboundConfig>> ordered = new ArrayList<>(newConfigs.entrySet());
        ordered.sort(Comparator.comparingInt(e -> e.getValue().getProtocol() == ProtocolType.TP_PROXY ? 0
                : e.getValue().getProtocol() == ProtocolType.DNS_SERVER ? 2 : 1));
        for (Map.Entry<Long, InboundConfig> e : ordered) {
            Long id = e.getKey();
            InboundConfig newCfg = e.getValue();
            InboundConfig oldCfg = configs.get(id);
            AbstractProxyServer existing = servers.get(id);

            boolean needsRestart = existing == null || !existing.isRunning()
                    || oldCfg == null || listenerChanged(oldCfg, newCfg);
            if (needsRestart) {
                activeInboundConsumer.accept(id);
                // 关闭旧服务
                if (existing != null) {
                    try {
                        existing.close();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }

                // 启动新服务
                AbstractProxyServer newServer = ProxyServerFactory.create(newCfg);
                newServer.updateRuntimeConfig(newCfg, dnsSnapshot);
                newServer.start();
                servers.put(id, newServer);
                configs.put(id, newCfg);
            } else {
                existing.updateRuntimeConfig(newCfg, dnsSnapshot);
                configs.put(id, newCfg);
                log.info("入站运行时快照已原子更新，监听端口保持不变: {}", id);
            }
        }
        enabledDnsInbound = configs.values().stream()
                .filter(config -> config.getProtocol() == ProtocolType.DNS_SERVER).findFirst();
    }

    private void validate(List<InboundConfig> inbounds) {
        if (inbounds == null) return;
        List<InboundConfig> dns = inbounds.stream().filter(Objects::nonNull)
                .filter(inbound -> inbound.getProtocol() == ProtocolType.DNS_SERVER).toList();
        List<InboundConfig> transparent = inbounds.stream().filter(Objects::nonNull)
                .filter(inbound -> inbound.getProtocol() == ProtocolType.TP_PROXY).toList();
        if (dns.size() > 1) throw new IllegalArgumentException("worker 存在多个启用的 DNS 入站");
        if (transparent.size() > 1) throw new IllegalArgumentException("worker 存在多个启用的透明代理入站");
        if (inbounds.stream().filter(Objects::nonNull).anyMatch(inbound -> Boolean.TRUE.equals(inbound.getFakeIpEnabled())
                && inbound.getProtocol() != ProtocolType.DNS_SERVER)) {
            throw new IllegalArgumentException("Fake IP 开关只能用于 DNS 入站");
        }
        if (dns.isEmpty() || !Boolean.TRUE.equals(dns.get(0).getFakeIpEnabled())) return;
        if (transparent.isEmpty()) throw new IllegalArgumentException("启用 Fake IP 需要透明代理入站");
        validateLocalPoolOverlap();
        InboundConfig dnsConfig = dns.get(0);
        RouteConfig fallback = dnsConfig.getDefaultRouteConfig();
        if (!isDnsForward(fallback)) {
            throw new IllegalArgumentException("Fake IP 的 DNS 兜底路由必须配置真实 DNS/DoT 转发");
        }
        validateDnsUpstream(fallback, dnsConfig);
        if (dnsConfig.getRoutesMap() != null) {
            dnsConfig.getRoutesMap().values().stream().filter(Objects::nonNull).flatMap(List::stream)
                    .filter(this::isDnsForward).forEach(route -> validateDnsUpstream(route, dnsConfig));
        }
        InboundConfig transparentConfig = transparent.get(0);
        List<RouteConfig> routes = new ArrayList<>();
        if (transparentConfig.getDefaultRouteConfig() != null) routes.add(transparentConfig.getDefaultRouteConfig());
        if (transparentConfig.getRoutesMap() != null) {
            transparentConfig.getRoutesMap().values().stream().filter(Objects::nonNull).forEach(routes::addAll);
        }
        if (routes.stream().filter(Objects::nonNull).anyMatch(route -> route.getRules() != null
                && route.getRules().stream().anyMatch(rule -> rule != null && rule.getConditionType() == RouteConditionType.GEO))) {
            throw new IllegalArgumentException("启用 Fake IP 的透明代理不能使用 GEO 路由条件");
        }
    }

    private boolean isDnsForward(RouteConfig route) {
        return route != null && route.getPolicy() == RoutePolicy.OUTBOUND_PROXY
                && (route.getOutboundProxyType() == ProtocolType.DNS_SERVER
                || route.getOutboundProxyType() == ProtocolType.DOT);
    }

    private void validateDnsUpstream(RouteConfig route, InboundConfig dns) {
        String raw = route.getOutboundProxyHost();
        Integer port = route.getOutboundProxyPort();
        if (raw == null || raw.isBlank() || port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("DNS 上游端点无效: " + route.getName());
        }
        for (String host : raw.trim().split("[,\\s]+")) {
            if (!InetAddresses.isInetAddress(host)) {
                throw new IllegalArgumentException("DNS 上游必须使用 IP 字面量: " + host);
            }
            java.net.InetAddress address = InetAddresses.forString(host);
            byte[] bytes = address.getAddress();
            if (bytes.length == 4 && (bytes[0] & 0xff) == 198
                    && ((bytes[1] & 0xff) == 18 || (bytes[1] & 0xff) == 19)) {
                throw new IllegalArgumentException("DNS 上游地址不能位于 Fake IP 地址池: " + host);
            }
            if (port.equals(dns.getPort()) && (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || host.equals(dns.getListenIp()))) {
                throw new IllegalArgumentException("DNS 上游不能指回本机 Fake IP DNS: " + host + ":" + port);
            }
        }
    }

    private void validateLocalPoolOverlap() {
        try {
            Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return;
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    byte[] bytes = address.getAddress().getAddress();
                    int prefix = address.getNetworkPrefixLength();
                    if (bytes.length != 4 || prefix < 0 || prefix > 32) continue;
                    long value = ((long) (bytes[0] & 0xff) << 24) | ((long) (bytes[1] & 0xff) << 16)
                            | ((long) (bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
                    long mask = prefix == 0 ? 0 : (0xffff_ffffL << (32 - prefix)) & 0xffff_ffffL;
                    long first = value & mask;
                    long last = first | (~mask & 0xffff_ffffL);
                    if (first <= 0xc613_ffffL && last >= 0xc612_0000L) {
                        throw new IllegalArgumentException("Fake IP 地址池与本机网络重叠: " + network.getName()
                                + " " + address.getAddress().getHostAddress() + "/" + prefix);
                    }
                }
            }
        } catch (java.net.SocketException error) {
            throw new IllegalArgumentException("无法验证 Fake IP 地址池与本机网络的关系", error);
        }
    }

    private boolean listenerChanged(InboundConfig oldConfig, InboundConfig newConfig) {
        return !Objects.equals(oldConfig.getProtocol(), newConfig.getProtocol())
                || !Objects.equals(oldConfig.getListenIp(), newConfig.getListenIp())
                || !Objects.equals(oldConfig.getPort(), newConfig.getPort());
    }
    public synchronized void closeAll() {
        for (AbstractProxyServer proxyServer : servers.values()) {
            try {
                proxyServer.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        servers.clear();
        configs.clear();
        enabledDnsInbound = Optional.empty();
    }

    public synchronized int getRunningServerCount() {
        int count = 0;
        for (AbstractProxyServer server : servers.values()) {
            if (server != null && server.isRunning()) {
                count++;
            }
        }
        return count;
    }

    public Optional<InboundConfig> getEnabledDnsInbound() {
        return enabledDnsInbound;
    }

    public int getActiveConnectionCount() {
        return activeConnectionCount.get();
    }

    public void incrementActiveConnectionCount() {
        activeConnectionCount.incrementAndGet();
    }

    public void decrementActiveConnectionCount() {
        activeConnectionCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    void resetActiveConnectionCountForTest() {
        activeConnectionCount.set(0);
    }

    synchronized AbstractProxyServer getServerForTest(Long inboundId) {
        return servers.get(inboundId);
    }



}
