package org.congcong.proxyworker.server.impl;
import io.netty.channel.*;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.tlssni.TlsSniServerInitializer;
import org.congcong.proxyworker.server.TcpProxyServer;
public class TlsSniProxyServer extends TcpProxyServer {
 public TlsSniProxyServer(InboundConfig cfg){super(cfg);} public int getPort(){return getInboundConfig().getPort();} public String getIp(){return getInboundConfig().getListenIp();} public String getServerName(){return getInboundConfig().getName();}
 protected ChannelInitializer<Channel> createChildHandler(InboundConfig c){return new TlsSniServerInitializer(c);}
}
