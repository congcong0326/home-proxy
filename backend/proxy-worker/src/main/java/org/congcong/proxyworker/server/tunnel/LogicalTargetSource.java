package org.congcong.proxyworker.server.tunnel;

public enum LogicalTargetSource {
    FAKE_IP,
    HTTP_HOST,
    TLS_SNI,
    INBOUND_REQUEST,
    ORIGINAL_IP
}
