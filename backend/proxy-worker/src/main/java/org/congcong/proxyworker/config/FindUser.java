package org.congcong.proxyworker.config;

public class FindUser {

    private static final UserQueryService delegate = new UserQueryService();

    public static UserConfig find(String key, InboundConfig inboundConfig) {
        return delegate.getUserConfig(key, inboundConfig);
    }

}
