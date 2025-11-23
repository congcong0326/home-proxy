package org.congcong.proxyworker.server.tunnel;

import io.netty.handler.codec.dns.DnsRecordType;
import lombok.Getter;

import java.net.InetSocketAddress;

@Getter
public class DnsProxyContext {
    private final int id;
    private final String qName;
    private final DnsRecordType qType;
    private final InetSocketAddress client;  // 新增

    public DnsProxyContext(int id, String qName, DnsRecordType qType, InetSocketAddress client) {
        this.id = id;
        this.qName = qName;
        this.qType = qType;
        this.client = client;
    }


}
