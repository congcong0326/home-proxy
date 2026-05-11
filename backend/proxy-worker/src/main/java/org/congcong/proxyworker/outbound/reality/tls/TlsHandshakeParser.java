package org.congcong.proxyworker.outbound.reality.tls;

import java.util.List;

public final class TlsHandshakeParser {

    public List<TlsHandshakeMessage> parse(byte[] payload) {
        return new TlsHandshakeMessageBuffer().append(payload);
    }
}
