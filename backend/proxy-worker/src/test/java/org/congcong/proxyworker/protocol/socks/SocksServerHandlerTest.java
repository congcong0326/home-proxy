package org.congcong.proxyworker.protocol.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SocksServerHandlerTest {
    private UserConfig user;
    private InboundConfig inbound;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        user = ProxyWorkerTestFixtures.user(7L, "alice", "secret", null);
        inbound = ProxyWorkerTestFixtures.socksInbound(user);
        channel = new EmbeddedChannel(new Socks5InitialRequestDecoder(), SocksServerHandler.getInstance());
        ChannelAttributes.setInboundConfig(channel, inbound);
        ChannelAttributes.setProxyContext(channel, new ProxyContext());
        ChannelAttributes.setProxyTimeContext(channel, new ProxyTimeContext());
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void selectsPasswordAuthenticationWhenClientSupportsIt() {
        channel.writeInbound(new DefaultSocks5InitialRequest(Socks5AuthMethod.NO_AUTH, Socks5AuthMethod.PASSWORD));

        DefaultSocks5InitialResponse response = channel.readOutbound();
        assertEquals(Socks5AuthMethod.PASSWORD, response.authMethod());
        assertNotNull(channel.pipeline().get("socks5PasswordAuthDecoder"));
    }

    @Test
    void rejectsClientWithoutPasswordAuthentication() {
        channel.writeInbound(new DefaultSocks5InitialRequest(Socks5AuthMethod.NO_AUTH));
        channel.runPendingTasks();

        DefaultSocks5InitialResponse response = channel.readOutbound();
        assertEquals(Socks5AuthMethod.UNACCEPTED, response.authMethod());
        assertFalse(channel.isOpen());
    }

    @Test
    void storesAuthenticatedUserAndAddsCommandDecoderWhenPasswordMatches() {
        negotiatePassword();

        channel.writeInbound(new DefaultSocks5PasswordAuthRequest("alice", "secret"));

        DefaultSocks5PasswordAuthResponse response = channel.readOutbound();
        assertEquals(Socks5PasswordAuthStatus.SUCCESS, response.status());
        assertSame(user, ChannelAttributes.getAuthenticatedUser(channel));
        assertNotNull(channel.pipeline().get("socks5CommandReqDecoder"));
    }

    @Test
    void rejectsInvalidPasswordAndClosesChannel() {
        negotiatePassword();

        channel.writeInbound(new DefaultSocks5PasswordAuthRequest("alice", "bad"));
        channel.runPendingTasks();

        DefaultSocks5PasswordAuthResponse response = channel.readOutbound();
        assertEquals(Socks5PasswordAuthStatus.FAILURE, response.status());
        assertFalse(channel.isOpen());
    }

    @Test
    void convertsAuthenticatedConnectCommandToProxyTunnelRequest() {
        negotiatePassword();
        authenticate();

        channel.writeInbound(new DefaultSocks5CommandRequest(
                Socks5CommandType.CONNECT,
                Socks5AddressType.DOMAIN,
                "example.com",
                443));

        ProxyTunnelRequest request = channel.readInbound();
        assertEquals(ProtocolType.SOCKS5, request.getProtocolType());
        assertEquals("example.com", request.getTargetHost());
        assertEquals(443, request.getTargetPort());
        assertSame(user, request.getUser());
        assertSame(inbound, request.getInboundConfig());
        assertEquals(user.getId(), ChannelAttributes.getProxyContext(channel).getUserId());
        assertEquals(user.getUsername(), ChannelAttributes.getProxyContext(channel).getUserName());
    }

    private void negotiatePassword() {
        channel.writeInbound(new DefaultSocks5InitialRequest(Socks5AuthMethod.PASSWORD));
        channel.readOutbound();
    }

    private void authenticate() {
        channel.writeInbound(new DefaultSocks5PasswordAuthRequest("alice", "secret"));
        channel.readOutbound();
    }
}
