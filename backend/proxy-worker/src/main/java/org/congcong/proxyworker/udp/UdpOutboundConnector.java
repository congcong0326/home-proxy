package org.congcong.proxyworker.udp;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;

public interface UdpOutboundConnector {
    Future<Channel> open(UdpSession session, EventLoop eventLoop);
}
