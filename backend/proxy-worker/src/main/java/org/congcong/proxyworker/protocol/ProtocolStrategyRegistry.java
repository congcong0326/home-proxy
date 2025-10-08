package org.congcong.proxyworker.protocol;

import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocksProtocolStrategy;
import org.congcong.proxyworker.protocol.socks.Socks5ProtocolStrategy;
import org.congcong.proxyworker.protocol.http.HttpsConnectProtocolStrategy;

import java.util.EnumMap;
import java.util.Map;

public class ProtocolStrategyRegistry {


    private static final Map<ProtocolType, ProtocolStrategy> STRATEGIES = new EnumMap<>(ProtocolType.class);

    static {
        // 这里注册各协议策略实现。先给出 SOCKS5 的示例，其它协议可后续扩展。
        STRATEGIES.put(ProtocolType.SOCKS5, new Socks5ProtocolStrategy());
        STRATEGIES.put(ProtocolType.HTTPS_CONNECT, new HttpsConnectProtocolStrategy());
        STRATEGIES.put(ProtocolType.SHADOW_SOCKS, new ShadowSocksProtocolStrategy());
    }

    private ProtocolStrategyRegistry() {}

    public static ProtocolStrategy get(ProtocolType type) {
        return STRATEGIES.get(type);
    }

}
