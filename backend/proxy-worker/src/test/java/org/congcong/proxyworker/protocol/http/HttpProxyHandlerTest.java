package org.congcong.proxyworker.protocol.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.proxyworker.integration.support.ProxyWorkerIntegrationFixtures;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HttpProxyHandlerTest {
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new HttpRequestDecoder(), new HttpServerInitializer.ResponseEncoder(),
                new HttpForwardHandler(), new HttpObjectAggregator(1_048_576), HttpServerHandler.getInstance());
        ChannelAttributes.setInboundConfig(channel,
                ProxyWorkerIntegrationFixtures.httpInbound(8080, List.of(), ProxyWorkerIntegrationFixtures.directRoute()));
        ChannelAttributes.setProxyContext(channel, new ProxyContext());
        ChannelAttributes.setProxyTimeContext(channel, new ProxyTimeContext());
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET http://example.com/", "CONNECT example.com:443"})
    void requiresAuthenticationForBothRequestModes(String request) {
        send(request + " HTTP/1.1\r\nHost: example.com\r\n\r\n");
        assertTrue(outbound().startsWith("HTTP/1.1 407"));
        assertNull(channel.readInbound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET http://example.com/", "CONNECT example.com:443"})
    void rejectsInvalidCredentialsForBothRequestModes(String request) {
        send(request + " HTTP/1.1\r\nHost: example.com\r\nProxy-Authorization: Basic "
                + token("it-user:wrong") + "\r\n\r\n");
        assertTrue(outbound().startsWith("HTTP/1.1 407"));
        assertNull(channel.readInbound());
    }

    @Test
    void rewritesAbsoluteUriAndRemovesProxyAndConnectionCredentials() {
        send("GET http://example.com:8081/a%2Fb?q=%2F HTTP/1.1\r\n"
                + auth() + "Host: wrong.example\r\nConnection: keep-alive, X-Hop\r\n"
                + "X-Hop: internal\r\nProxy-Connection: keep-alive\r\nAuthorization: Bearer origin-token\r\n\r\n");
        ProxyTunnelRequest request = channel.readInbound();
        assertEquals("example.com", request.getTargetHost());
        assertEquals(8081, request.getTargetPort());
        assertEquals("it-user", request.getUser().getUsername());
        assertTrue(HttpForwardHandler.isForwardRequest(request));
        String forwarded = inboundBytes();
        assertTrue(forwarded.startsWith("GET /a%2Fb?q=%2F HTTP/1.1\r\n"), forwarded);
        assertTrue(forwarded.contains("host: example.com:8081\r\n"), forwarded);
        assertTrue(forwarded.contains("connection: close\r\n"), forwarded);
        assertTrue(forwarded.contains("Authorization: Bearer origin-token\r\n"), forwarded);
        assertFalse(forwarded.toLowerCase().contains("proxy-"), forwarded);
        assertFalse(forwarded.contains("X-Hop"), forwarded);
        assertEquals("", outbound());
    }

    @Test
    void supportsOriginFormUsingHostAndIpv6Literal() {
        send("GET /path?q=1 HTTP/1.1\r\n" + auth() + "Host: [2001:db8::1]:8081\r\n\r\n");
        ProxyTunnelRequest request = channel.readInbound();
        assertEquals("2001:db8::1", request.getTargetHost());
        assertEquals(8081, request.getTargetPort());
        assertTrue(inboundBytes().contains("host: [2001:db8::1]:8081\r\n"));
    }

    @Test
    void defaultsToPort80AndPreservesQueryForEmptyAbsolutePath() {
        send("GET http://example.com?q=%2F HTTP/1.1\r\n" + auth() + "Host: example.com\r\n\r\n");
        assertEquals(80, channel.<ProxyTunnelRequest>readInbound().getTargetPort());
        assertTrue(inboundBytes().startsWith("GET /?q=%2F HTTP/1.1\r\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Host: first.example\r\nHost: second.example\r\n", "Host: example.com/path\r\n",
            "Host: example.com\r\nConnection: Content-Length\r\n"})
    void rejectsAmbiguousHostOrConnectionFraming(String headers) {
        send("GET /path HTTP/1.1\r\n" + auth() + headers + "\r\n");
        assertTrue(outbound().startsWith("HTTP/1.1 400"));
        assertNull(channel.readInbound());
    }

    @Test
    void streamsChunkedBodyAndSanitizesTrailers() {
        send("POST http://example.com/upload HTTP/1.1\r\n" + auth()
                + "Host: example.com\r\nTransfer-Encoding: chunked\r\n\r\n");
        assertNotNull(channel.<ProxyTunnelRequest>readInbound());
        String forwarded = inboundBytes();
        assertTrue(forwarded.toLowerCase().contains("transfer-encoding: chunked"));
        send("4\r\ntest\r\n0\r\nProxy-Authorization: secret\r\nX-Checksum: abc\r\n\r\n");
        forwarded = inboundBytes();
        assertTrue(forwarded.contains("4\r\ntest\r\n0\r\n"), forwarded);
        assertTrue(forwarded.contains("X-Checksum: abc"), forwarded);
        assertFalse(forwarded.contains("secret"), forwarded);
    }

    @Test
    void doesNotForwardPipelinedRequestToFirstOrigin() {
        send("GET http://first.example/ HTTP/1.1\r\n" + auth() + "Host: first.example\r\n\r\n"
                + "GET http://second.example/ HTTP/1.1\r\n" + auth() + "Host: second.example\r\n\r\n");
        assertEquals("first.example", channel.<ProxyTunnelRequest>readInbound().getTargetHost());
        String forwarded = inboundBytes();
        assertFalse(forwarded.contains("second.example"), forwarded);
        assertFalse(forwarded.contains("Proxy-Authorization"), forwarded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/", "http://user:pass@example.com/", "http://example.com:0/",
            "http://example.com:65536/", "http://example.com/#fragment", "/path#fragment", "//example.com/", "invalid"})
    void rejectsInvalidTarget(String target) {
        send("GET " + target + " HTTP/1.1\r\n" + auth() + "Host: example.com\r\n\r\n");
        assertTrue(outbound().startsWith("HTTP/1.1 400"));
        assertNull(channel.readInbound());
    }

    @Test
    void connectStillReturnsTunnelSuccessAndRelaysCoalescedPayload() {
        send("CONNECT example.com:443 HTTP/1.1\r\n" + auth() + "Host: example.com:443\r\n\r\ntls-payload");
        ProxyTunnelRequest request = channel.readInbound();
        assertFalse(HttpForwardHandler.isForwardRequest(request));
        assertEquals(443, request.getTargetPort());
        assertEquals("tls-payload", inboundBytes());
        EmbeddedChannel upstream = new EmbeddedChannel();
        try {
            new HttpsConnectProtocolStrategy().onConnectSuccess(channel.pipeline().firstContext(), upstream, request);
            assertTrue(outbound().startsWith("HTTP/1.1 200 Connection Established"));
        } finally {
            upstream.finishAndReleaseAll();
        }
    }

    @Test
    void ordinaryHttpDoesNotSendConnectSuccess() {
        send("GET http://example.com/ HTTP/1.1\r\n" + auth() + "Host: example.com\r\n\r\n");
        ProxyTunnelRequest request = channel.readInbound();
        inboundBytes();
        ChannelHandlerContext ctx = channel.pipeline().context(HttpForwardHandler.class);
        EmbeddedChannel upstream = new EmbeddedChannel();
        try {
            new HttpsConnectProtocolStrategy().onConnectSuccess(ctx, upstream, request);
            assertEquals("", outbound());
            assertTrue(channel.config().isAutoRead());
        } finally {
            upstream.finishAndReleaseAll();
        }
    }

    private void send(String value) {
        channel.writeInbound(Unpooled.copiedBuffer(value, CharsetUtil.US_ASCII));
    }

    private static String auth() {
        return "Proxy-Authorization: Basic " + token("it-user:it-pass") + "\r\n";
    }

    private static String token(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(CharsetUtil.UTF_8));
    }

    private String inboundBytes() {
        StringBuilder result = new StringBuilder();
        ByteBuf value;
        while ((value = channel.readInbound()) != null) {
            result.append(value.toString(CharsetUtil.US_ASCII));
            value.release();
        }
        return result.toString();
    }

    private String outbound() {
        StringBuilder result = new StringBuilder();
        ByteBuf value;
        while ((value = channel.readOutbound()) != null) {
            result.append(value.toString(CharsetUtil.US_ASCII));
            value.release();
        }
        return result.toString();
    }
}
