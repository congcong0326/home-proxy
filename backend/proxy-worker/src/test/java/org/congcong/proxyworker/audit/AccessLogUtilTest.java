package org.congcong.proxyworker.audit;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessLogUtilTest {
    @Test
    void legacySuccessFailureAndDnsCollectionRemainDisabled() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ProxyContext context = new ProxyContext();
            ProxyTimeContext timing = new ProxyTimeContext();
            ChannelAttributes.setProxyContext(channel, context);
            ChannelAttributes.setProxyTimeContext(channel, timing);
            AccessLogUtil.logSuccess(channel);
            AccessLogUtil.logFailure(channel, 502, "CONNECT_FAILED", "refused");
            AccessLogUtil.logDns(context, timing, new DnsProxyContext(1, "example.test", DnsRecordType.A,
                    new InetSocketAddress("127.0.0.1", 53000)), DnsResponseCode.NOERROR, List.of("192.0.2.1"));
            assertTrue(AccessLogUtil.drainAccessLogs(10).isEmpty());
            assertTrue(AccessLogUtil.drainAuthLogs(10).isEmpty());
        } finally {
            channel.finishAndReleaseAll();
            AccessLogUtil.stop();
        }
    }
}
