package org.congcong.proxyworker.config;

import org.congcong.common.enums.ProtocolType;

public class UserQueryService   {

    public UserConfig getUserConfig(String key, InboundConfig inboundConfig) {
        ProtocolType protocol = inboundConfig.getProtocol();
        switch (protocol) {
            case SOCKS5,HTTPS_CONNECT -> {
                return inboundConfig.getUsersMap().get(key);
            }
            case SHADOW_SOCKS -> {
                return inboundConfig.getUsersMap().values().stream().findAny().orElse(null);
            }
            case TP_PROXY -> {
                UserConfig userConfig = inboundConfig.getDeviceIpMapUser().get(key);
                if (userConfig != null) {
                    return userConfig;
                }
            }
        }
        // 目前只有TP_PROXY允许匿名访问
        return inboundConfig.getAnonymousUser();
    }
}
