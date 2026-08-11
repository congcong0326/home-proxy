package org.congcong.proxyworker.protocol.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

class SocksServerHandlerTest {
    private UserConfig user;
    private InboundConfig inbound;
    private EmbeddedChannel channel;
    private AtomicLong now;
    private SocksAuthBruteForceProtector bruteForceProtector;
    private SocksServerHandler handler;

    @BeforeEach
    void setUp() {
        user = ProxyWorkerTestFixtures.user(7L, "alice", "secret", null);
        inbound = ProxyWorkerTestFixtures.socksInbound(user);
        now = new AtomicLong();
        bruteForceProtector = new SocksAuthBruteForceProtector(now::get);
        handler = new SocksServerHandler(bruteForceProtector);
        channel = newChannel();
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
    void rejectsCorrectCredentialsWhileLockedThenPermitsAfterLockExpires() {
        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES; i++) {
            EmbeddedChannel failedAttempt = newPasswordNegotiatedChannel();
            assertAuthenticationFailure(failedAttempt, "alice", "bad-password");
            failedAttempt.finishAndReleaseAll();
        }

        EmbeddedChannel lockedAttempt = newPasswordNegotiatedChannel();
        assertAuthenticationFailure(lockedAttempt, "alice", "secret");
        lockedAttempt.finishAndReleaseAll();

        now.addAndGet(SocksAuthBruteForceProtector.LOCK_DURATION_MILLIS + 1);
        EmbeddedChannel unlockedAttempt = newPasswordNegotiatedChannel();
        unlockedAttempt.writeInbound(new DefaultSocks5PasswordAuthRequest("alice", "secret"));

        DefaultSocks5PasswordAuthResponse response = unlockedAttempt.readOutbound();
        assertEquals(Socks5PasswordAuthStatus.SUCCESS, response.status());
        assertTrue(unlockedAttempt.isOpen());
        unlockedAttempt.finishAndReleaseAll();
    }

    @Test
    void clearsFailuresAfterSuccessfulAuthentication() {
        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES - 1; i++) {
            EmbeddedChannel failedAttempt = newPasswordNegotiatedChannel();
            assertAuthenticationFailure(failedAttempt, "alice", "bad-password");
            failedAttempt.finishAndReleaseAll();
        }

        EmbeddedChannel successfulAttempt = newPasswordNegotiatedChannel();
        successfulAttempt.writeInbound(new DefaultSocks5PasswordAuthRequest("alice", "secret"));
        DefaultSocks5PasswordAuthResponse response = successfulAttempt.readOutbound();
        assertEquals(Socks5PasswordAuthStatus.SUCCESS, response.status());
        successfulAttempt.finishAndReleaseAll();

        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES - 1; i++) {
            EmbeddedChannel failedAttempt = newPasswordNegotiatedChannel();
            assertAuthenticationFailure(failedAttempt, "alice", "bad-password");
            failedAttempt.finishAndReleaseAll();
        }

        assertFalse(bruteForceProtector.isLocked("alice"));
    }

    @Test
    void returnsTheSameFailureResponseForLockedKnownAndUnknownUsernames() {
        lockUsername("alice");
        EmbeddedChannel knownUserAttempt = newPasswordNegotiatedChannel();
        knownUserAttempt.writeInbound(new DefaultSocks5PasswordAuthRequest("alice", "secret"));
        knownUserAttempt.runPendingTasks();
        DefaultSocks5PasswordAuthResponse knownResponse = knownUserAttempt.readOutbound();
        assertFalse(knownUserAttempt.isOpen());
        knownUserAttempt.finishAndReleaseAll();

        lockUsername("does-not-exist");
        EmbeddedChannel unknownUserAttempt = newPasswordNegotiatedChannel();
        unknownUserAttempt.writeInbound(new DefaultSocks5PasswordAuthRequest("does-not-exist", "any-password"));
        unknownUserAttempt.runPendingTasks();
        DefaultSocks5PasswordAuthResponse unknownResponse = unknownUserAttempt.readOutbound();
        assertFalse(unknownUserAttempt.isOpen());
        unknownUserAttempt.finishAndReleaseAll();

        assertEquals(Socks5PasswordAuthStatus.FAILURE, knownResponse.status());
        assertEquals(knownResponse.status(), unknownResponse.status());
    }

    @Test
    void doesNotLogSubmittedPasswordWhenAuthenticationFails() {
        String submittedPassword = "sensitive-password-123";
        Logger logger = (Logger) LoggerFactory.getLogger(SocksServerHandler.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            negotiatePassword();
            assertAuthenticationFailure(channel, "alice", submittedPassword);

            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("invalid credentials")));
            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(submittedPassword)));
        } finally {
            logger.setLevel(originalLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
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

    private EmbeddedChannel newChannel() {
        EmbeddedChannel newChannel = new EmbeddedChannel(new Socks5InitialRequestDecoder(), handler);
        ChannelAttributes.setInboundConfig(newChannel, inbound);
        ChannelAttributes.setProxyContext(newChannel, new ProxyContext());
        ChannelAttributes.setProxyTimeContext(newChannel, new ProxyTimeContext());
        return newChannel;
    }

    private EmbeddedChannel newPasswordNegotiatedChannel() {
        EmbeddedChannel newChannel = newChannel();
        newChannel.writeInbound(new DefaultSocks5InitialRequest(Socks5AuthMethod.PASSWORD));
        newChannel.readOutbound();
        return newChannel;
    }

    private void assertAuthenticationFailure(EmbeddedChannel target, String username, String password) {
        target.writeInbound(new DefaultSocks5PasswordAuthRequest(username, password));
        target.runPendingTasks();

        DefaultSocks5PasswordAuthResponse response = target.readOutbound();
        assertEquals(Socks5PasswordAuthStatus.FAILURE, response.status());
        assertFalse(target.isOpen());
    }

    private void lockUsername(String username) {
        for (int i = 0; i < SocksAuthBruteForceProtector.MAX_FAILURES; i++) {
            EmbeddedChannel failedAttempt = newPasswordNegotiatedChannel();
            assertAuthenticationFailure(failedAttempt, username, "bad-password");
            failedAttempt.finishAndReleaseAll();
        }
        assertTrue(bruteForceProtector.isLocked(username));
    }
}
