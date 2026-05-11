package org.congcong.proxyworker.integration.support;

public final class XrayConfigs {
    private XrayConfigs() {
    }

    public static String socksInbound(int port) {
        return """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [{
                    "listen": "127.0.0.1",
                    "port": %d,
                    "protocol": "socks",
                    "settings": { "auth": "noauth", "udp": false }
                  }],
                  "outbounds": [{ "protocol": "freedom" }]
                }
                """.formatted(port);
    }

    public static String httpInbound(int port) {
        return """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [{
                    "listen": "127.0.0.1",
                    "port": %d,
                    "protocol": "http",
                    "settings": {}
                  }],
                  "outbounds": [{ "protocol": "freedom" }]
                }
                """.formatted(port);
    }

    public static String shadowsocksInbound(int port, String method, String password) {
        return """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [{
                    "listen": "127.0.0.1",
                    "port": %d,
                    "protocol": "shadowsocks",
                    "settings": {
                      "method": "%s",
                      "password": "%s",
                      "network": "tcp"
                    }
                  }],
                  "outbounds": [{ "protocol": "freedom" }]
                }
                """.formatted(port, method, password);
    }

    public static String socksClientToWorkerSocks(int localSocksPort, int workerSocksPort, String user, String pass) {
        return """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [{
                    "listen": "127.0.0.1",
                    "port": %d,
                    "protocol": "socks",
                    "settings": { "auth": "noauth", "udp": false }
                  }],
                  "outbounds": [{
                    "protocol": "socks",
                    "settings": {
                      "servers": [{
                        "address": "127.0.0.1",
                        "port": %d,
                        "users": [{ "user": "%s", "pass": "%s" }]
                      }]
                    }
                  }]
                }
                """.formatted(localSocksPort, workerSocksPort, user, pass);
    }

    public static String socksClientToWorkerHttp(int localSocksPort, int workerHttpPort, String user, String pass) {
        return """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [{
                    "listen": "127.0.0.1",
                    "port": %d,
                    "protocol": "socks",
                    "settings": { "auth": "noauth", "udp": false }
                  }],
                  "outbounds": [{
                    "protocol": "http",
                    "settings": {
                      "servers": [{
                        "address": "127.0.0.1",
                        "port": %d,
                        "users": [{ "user": "%s", "pass": "%s" }]
                      }]
                    }
                  }]
                }
                """.formatted(localSocksPort, workerHttpPort, user, pass);
    }
}
