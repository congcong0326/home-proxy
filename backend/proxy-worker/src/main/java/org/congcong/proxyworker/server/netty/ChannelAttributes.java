package org.congcong.proxyworker.server.netty;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public final class ChannelAttributes {

    private ChannelAttributes() {}

    public static final AttributeKey<InboundConfig> INBOUND_CONFIG = AttributeKey.valueOf("inboundConfig");
    public static final AttributeKey<UserConfig> AUTHENTICATED_USER = AttributeKey.valueOf("authenticatedUser");

    public static final AttributeKey<ProxyContext> PROXY_CONTEXT_ATTRIBUTE_KEY = AttributeKey.valueOf("PROXY_CONTEXT_ATTRIBUTE_KEY");
    public static final AttributeKey<ProxyTimeContext> PROXY_TIME_ATTRIBUTE_KEY = AttributeKey.valueOf("PROXY_TIME_ATTRIBUTE_KEY");

    public static final AttributeKey<ProxyTunnelRequest> ProxyTunnelRequest = AttributeKey.valueOf("ProxyTunnelRequest");
    public static final AttributeKey<byte[]> SHADOWSOCKS_2022_REQUEST_SALT = AttributeKey.valueOf("SHADOWSOCKS_2022_REQUEST_SALT");

    public static ProxyTunnelRequest getProxyTunnelRequest(Channel channel) {
        return channel.attr(ChannelAttributes.ProxyTunnelRequest).get();
    }

    public static void setProxyTunnelRequest(Channel channel, ProxyTunnelRequest proxyContext) {
        channel.attr(ChannelAttributes.ProxyTunnelRequest).set(proxyContext);
    }


    public static void removeProxyTunnelRequest(Channel channel) {
        channel.attr(ChannelAttributes.ProxyTunnelRequest).set(null);
    }

    public static byte[] getShadowSocks2022RequestSalt(Channel channel) {
        return channel.attr(SHADOWSOCKS_2022_REQUEST_SALT).get();
    }

    public static void setShadowSocks2022RequestSalt(Channel channel, byte[] requestSalt) {
        channel.attr(SHADOWSOCKS_2022_REQUEST_SALT).set(requestSalt);
    }


    public static ProxyContext getProxyContext(Channel channel) {
        return channel.attr(ChannelAttributes.PROXY_CONTEXT_ATTRIBUTE_KEY).get();
    }

    public static void setProxyContext(Channel channel, ProxyContext proxyContext) {
        channel.attr(ChannelAttributes.PROXY_CONTEXT_ATTRIBUTE_KEY).set(proxyContext);
    }

    public static ProxyTimeContext getProxyTimeContext(Channel channel) {
        return channel.attr(ChannelAttributes.PROXY_TIME_ATTRIBUTE_KEY).get();
    }

    public static void setProxyTimeContext(Channel channel, ProxyTimeContext proxyTimeContext) {
        channel.attr(ChannelAttributes.PROXY_TIME_ATTRIBUTE_KEY).set(proxyTimeContext);
    }


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
