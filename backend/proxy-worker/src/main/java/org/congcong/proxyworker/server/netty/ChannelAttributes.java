package org.congcong.proxyworker.server.netty;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;

public final class ChannelAttributes {

    private ChannelAttributes() {}

    public static final AttributeKey<InboundConfig> INBOUND_CONFIG = AttributeKey.valueOf("inboundConfig");
    public static final AttributeKey<UserConfig> AUTHENTICATED_USER = AttributeKey.valueOf("authenticatedUser");

    public static InboundConfig getInboundConfig(Channel channel) {
        return channel.attr(ChannelAttributes.INBOUND_CONFIG).get();
    }

    public static void setInboundConfig(Channel channel, InboundConfig inboundConfig) {
        channel.attr(ChannelAttributes.INBOUND_CONFIG).set(inboundConfig);
    }

    public static UserConfig getAuthenticatedUser(Channel channel) {
        return channel.attr(ChannelAttributes.AUTHENTICATED_USER).get();
    }

    public static void setAuthenticatedUser(Channel channel, UserConfig user) {
        channel.attr(ChannelAttributes.AUTHENTICATED_USER).set(user);
    }
}