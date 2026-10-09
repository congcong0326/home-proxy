package org.congcong.controlmanager.service;

import com.google.common.net.InetAddresses;
import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.repository.RouteRepository;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates combinations that depend on all enabled inbounds of one worker. */
@Component
@RequiredArgsConstructor
public class FakeIpConfigValidator {
    private final RouteRepository routeRepository;

    public void validate(List<InboundConfig> inbounds) {
        List<InboundConfig> enabled = inbounds.stream()
                .filter(inbound -> Integer.valueOf(1).equals(inbound.getStatus()))
                .toList();
        List<InboundConfig> dns = enabled.stream()
                .filter(inbound -> inbound.getProtocol() == ProtocolType.DNS_SERVER).toList();
        List<InboundConfig> transparent = enabled.stream()
                .filter(inbound -> inbound.getProtocol() == ProtocolType.TP_PROXY).toList();
        if (dns.size() > 1) throw new IllegalArgumentException("worker 存在多个启用的 DNS 入站");
        if (transparent.size() > 1) throw new IllegalArgumentException("worker 存在多个启用的透明代理入站");
        if (inbounds.stream().anyMatch(inbound -> Boolean.TRUE.equals(inbound.getFakeIpEnabled())
                && inbound.getProtocol() != ProtocolType.DNS_SERVER)) {
            throw new IllegalArgumentException("Fake IP 开关只能用于 DNS 入站");
        }
        if (dns.isEmpty() || !Boolean.TRUE.equals(dns.get(0).getFakeIpEnabled())) return;
        if (transparent.isEmpty()) throw new IllegalArgumentException("启用 Fake IP 需要同一 worker 的透明代理入站");

        InboundConfig dnsInbound = dns.get(0);
        Route fallback = requireRoute(dnsInbound.getFallbackRouteId());
        if (!isDnsForward(fallback)) {
            throw new IllegalArgumentException("Fake IP 的 DNS 兜底路由必须配置真实 DNS/DoT 转发");
        }
        Set<Long> dnsRouteIds = routeIds(dnsInbound);
        for (Long routeId : dnsRouteIds) {
            Route route = requireRoute(routeId);
            if (isDnsForward(route)) validateUpstream(route, dnsInbound);
        }
        validateUpstream(fallback, dnsInbound);

        for (Long routeId : routeIds(transparent.get(0))) {
            Route route = requireRoute(routeId);
            if (route.getRules() != null && route.getRules().stream().anyMatch(rule -> rule != null
                    && rule.getConditionType() == RouteConditionType.GEO)) {
                throw new IllegalArgumentException("启用 Fake IP 的透明代理不能使用 GEO 路由条件");
            }
        }
    }

    private Set<Long> routeIds(InboundConfig inbound) {
        Set<Long> ids = new HashSet<>();
        if (inbound.getFallbackRouteId() != null) ids.add(inbound.getFallbackRouteId());
        if (inbound.getInboundRouteBindings() != null) {
            for (InboundRouteBinding binding : inbound.getInboundRouteBindings()) {
                if (binding != null && binding.getRouteIds() != null) ids.addAll(binding.getRouteIds());
            }
        }
        ids.remove(null);
        return ids;
    }

    private Route requireRoute(Long id) {
        return id == null ? null : routeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("路由不存在: " + id));
    }

    private boolean isDnsForward(Route route) {
        return route != null && route.getPolicy() == RoutePolicy.OUTBOUND_PROXY
                && (route.getOutboundProxyType() == ProtocolType.DNS_SERVER
                || route.getOutboundProxyType() == ProtocolType.DOT);
    }

    private void validateUpstream(Route route, InboundConfig dnsInbound) {
        String hosts = route.getOutboundProxyHost();
        Integer port = route.getOutboundProxyPort();
        if (port == null || port < 1 || port > 65535 || hosts == null || hosts.isBlank()) {
            throw new IllegalArgumentException("DNS 上游端点无效: " + route.getName());
        }
        for (String host : hosts.trim().split("[,\\s]+")) {
            if (!InetAddresses.isInetAddress(host)) {
                throw new IllegalArgumentException("DNS 上游必须使用 IP 字面量: " + host);
            }
            InetAddress address = InetAddresses.forString(host);
            byte[] bytes = address.getAddress();
            if (bytes.length == 4 && (bytes[0] & 0xff) == 198
                    && ((bytes[1] & 0xff) == 18 || (bytes[1] & 0xff) == 19)) {
                throw new IllegalArgumentException("DNS 上游地址不能位于 Fake IP 地址池: " + host);
            }
            if (port.equals(dnsInbound.getPort()) && (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || host.equals(dnsInbound.getListenIp()))) {
                throw new IllegalArgumentException("DNS 上游不能指回本机 Fake IP DNS: " + host + ":" + port);
            }
        }
    }
}
