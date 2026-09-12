package org.congcong.proxyworker.protocol.tlssni;
import io.netty.channel.Channel;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.netty.AbstractChannelInitializer;
public class TlsSniServerInitializer extends AbstractChannelInitializer { public TlsSniServerInitializer(InboundConfig c){super(c);} protected void init(Channel ch){ch.pipeline().addLast(new TlsSniHandler());} }
