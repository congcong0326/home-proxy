package org.congcong.controlmanager.service;

import com.google.common.net.InetAddresses;
import lombok.RequiredArgsConstructor;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.controlmanager.dto.outbound.OutboundRequest;
import org.congcong.controlmanager.dto.outbound.OutboundView;
import org.congcong.controlmanager.entity.Outbound;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.OutboundKind;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 出站管理。内置的直连、阻断出站由迁移脚本和 {@link #ensureBuiltinOutboundsExist()} 维护，不可修改或删除。
 */
@Service
@RequiredArgsConstructor
public class OutboundService {
    public static final String BUILTIN_DIRECT = "DIRECT";
    public static final String BUILTIN_BLOCK = "BLOCK";

    private static final Set<ProtocolType> PROXY_TYPES = Set.of(
            ProtocolType.HTTPS_CONNECT, ProtocolType.SOCKS5, ProtocolType.SHADOW_SOCKS, ProtocolType.VLESS_REALITY);
    private static final Set<ProtocolType> DNS_TYPES = Set.of(ProtocolType.DNS_SERVER, ProtocolType.DOT);

    private final OutboundRepository outboundRepository;
    private final RouteRepository routeRepository;

    public List<OutboundView> getOutbounds() {
        Map<Long, Long> routeCounts = routeRepository.findAll().stream()
                .filter(route -> route.getOutboundId() != null)
                .collect(Collectors.groupingBy(Route::getOutboundId, Collectors.counting()));
        return outboundRepository.findAllByOrderByIdAsc().stream()
                .map(outbound -> toView(outbound, routeCounts.getOrDefault(outbound.getId(), 0L)))
                .toList();
    }

    public OutboundView getOutbound(Long id) {
        Outbound outbound = require(id);
        return toView(outbound, routeRepository.findByOutboundId(id).size());
    }

    public Outbound require(Long id) {
        return outboundRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "出站不存在: " + id));
    }

    public Outbound requireBuiltin(String builtinKey) {
        return outboundRepository.findByBuiltinKey(builtinKey)
                .orElseThrow(() -> new IllegalStateException("缺少内置出站: " + builtinKey));
    }

    public List<Route> getReferencingRoutes(Long id) {
        require(id);
        return routeRepository.findByOutboundId(id);
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public OutboundView createOutbound(OutboundRequest request) {
        if (outboundRepository.existsByName(request.getName().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "出站名称已存在");
        }
        Outbound outbound = new Outbound();
        apply(outbound, request);
        return toView(outboundRepository.save(outbound), 0);
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public OutboundView updateOutbound(Long id, OutboundRequest request) {
        Outbound outbound = require(id);
        if (outbound.isBuiltin()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置出站不能修改");
        }
        if (outboundRepository.existsByNameAndIdNot(request.getName().trim(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "出站名称已存在");
        }
        List<Route> references = routeRepository.findByOutboundId(id);
        if (request.getKind() != outbound.getKind() && !references.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "出站已被路由引用，不能修改类别：" + routeNames(references));
        }
        apply(outbound, request);
        return toView(outboundRepository.save(outbound), references.size());
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteOutbound(Long id) {
        Outbound outbound = require(id);
        if (outbound.isBuiltin()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置出站不能删除");
        }
        List<Route> references = routeRepository.findByOutboundId(id);
        if (!references.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "出站“" + outbound.getName() + "”被以下路由引用，无法删除：" + routeNames(references));
        }
        outboundRepository.delete(outbound);
    }

    /** Idempotently restores the built-in outbounds in case they were removed from the database by hand. */
    @Transactional
    public void ensureBuiltinOutboundsExist() {
        ensureBuiltin(BUILTIN_DIRECT, "直连", OutboundKind.DIRECT);
        ensureBuiltin(BUILTIN_BLOCK, "阻断", OutboundKind.BLOCK);
    }

    private void ensureBuiltin(String key, String name, OutboundKind kind) {
        if (outboundRepository.findByBuiltinKey(key).isPresent()) return;
        Outbound outbound = new Outbound();
        outbound.setBuiltinKey(key);
        outbound.setName(outboundRepository.existsByName(name) ? name + "（内置）" : name);
        outbound.setKind(kind);
        outboundRepository.save(outbound);
    }

    private void apply(Outbound outbound, OutboundRequest request) {
        validate(request);
        outbound.setName(request.getName().trim());
        outbound.setKind(request.getKind());
        outbound.setProxyType(request.getProxyType());
        outbound.setHost(request.getHost().trim());
        outbound.setPort(request.getPort());
        outbound.setUsername(blankToNull(request.getUsername()));
        outbound.setPassword(blankToNull(request.getPassword()));
        outbound.setEncAlgo(request.getProxyType() == ProtocolType.SHADOW_SOCKS ? request.getEncAlgo() : null);
        outbound.setConfig(request.getConfig());
        outbound.setNotes(request.getNotes());
    }

    private void validate(OutboundRequest request) {
        OutboundKind kind = request.getKind();
        ProtocolType type = request.getProxyType();
        if (kind == OutboundKind.PROXY) {
            if (!PROXY_TYPES.contains(type)) throw badRequest("上游代理出站不支持协议: " + type);
        } else if (kind == OutboundKind.DNS_UPSTREAM) {
            if (!DNS_TYPES.contains(type)) throw badRequest("DNS 上游出站只支持 DNS_SERVER / DOT: " + type);
        } else {
            throw badRequest("只能创建上游代理或 DNS 上游出站");
        }
        String host = requireText(request.getHost(), "出站主机地址不能为空");
        if (request.getPort() == null || request.getPort() < 1 || request.getPort() > 65535) {
            throw badRequest("出站端口必须在 1-65535 之间");
        }
        if (kind == OutboundKind.DNS_UPSTREAM) {
            for (String address : host.split("[,\\s]+")) {
                if (!InetAddresses.isInetAddress(address)) throw badRequest("DNS 上游必须使用 IP 字面量: " + address);
            }
        }
        if (type == ProtocolType.SHADOW_SOCKS) validateShadowsocks(request.getEncAlgo(), request.getPassword());
        if (type == ProtocolType.VLESS_REALITY) validateReality(request.getConfig());
    }

    private void validateReality(Map<String, Object> config) {
        if (config == null) throw badRequest("VLESS REALITY outboundProxyConfig is required");
        requireConfigText(config, "serverName", "VLESS REALITY serverName is required");
        requireConfigText(config, "publicKey", "VLESS REALITY publicKey is required");
        String shortId = requireConfigText(config, "shortId", "VLESS REALITY shortId is required");
        if (!shortId.matches("^[0-9a-fA-F]{0,16}$") || shortId.length() % 2 != 0) {
            throw badRequest("Invalid REALITY shortId hex: " + shortId);
        }
        String uuid = requireConfigText(config, "uuid", "VLESS REALITY uuid is required");
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid VLESS uuid: " + uuid, e);
        }
    }

    private void validateShadowsocks(ProxyEncAlgo encAlgo, String password) {
        if (encAlgo == null) throw badRequest("Shadowsocks 出站必须指定加密算法");
        if (!encAlgo.isShadowSocks2022()) return;
        if (password == null || password.isBlank()) throw badRequest("Shadowsocks 2022 出站必须填写 Base64 预共享密钥");
        try {
            for (String key : password.split(":")) {
                byte[] decoded = Base64.getDecoder().decode(key.trim());
                if (decoded.length != encAlgo.getPskLength()) {
                    throw badRequest("Shadowsocks 2022 每段预共享密钥都需要是 " + encAlgo.getPskLength() + " 字节 Base64 数据");
                }
            }
        } catch (IllegalArgumentException e) {
            throw badRequest("Shadowsocks 2022 预共享密钥必须是有效的 Base64 字符串，支持 iPSK:uPSK 形式");
        }
    }

    private OutboundView toView(Outbound outbound, long routeCount) {
        OutboundView view = new OutboundView();
        view.setId(outbound.getId());
        view.setName(outbound.getName());
        view.setKind(outbound.getKind());
        view.setBuiltin(outbound.isBuiltin());
        view.setProxyType(outbound.getProxyType());
        view.setHost(outbound.getHost());
        view.setPort(outbound.getPort());
        view.setUsername(outbound.getUsername());
        view.setPassword(outbound.getPassword());
        view.setEncAlgo(outbound.getEncAlgo());
        view.setConfig(outbound.getConfig());
        view.setNotes(outbound.getNotes());
        view.setRouteCount(routeCount);
        view.setCreatedAt(outbound.getCreatedAt());
        view.setUpdatedAt(outbound.getUpdatedAt());
        return view;
    }

    static String routeNames(List<Route> routes) {
        return routes.stream().map(route -> "“" + route.getName() + "”（ID：" + route.getId() + "）")
                .collect(Collectors.joining("、"));
    }

    private static String requireConfigText(Map<String, Object> config, String key, String message) {
        Object value = config.get(key);
        return requireText(value == null ? null : String.valueOf(value), message);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) throw badRequest(message);
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
