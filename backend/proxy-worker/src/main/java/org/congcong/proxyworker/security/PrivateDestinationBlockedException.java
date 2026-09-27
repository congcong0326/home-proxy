package org.congcong.proxyworker.security;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/** A distinct failure type for a destination rejected by address policy. */
public class PrivateDestinationBlockedException extends Exception {

    private final String host;
    private final int port;
    private final List<String> resolvedAddresses;

    public PrivateDestinationBlockedException(String host, int port, List<String> resolvedAddresses) {
        super("non-public destination blocked: " + host + ":" + port
                + (resolvedAddresses.isEmpty() ? "" : " (resolved: " + String.join(", ", resolvedAddresses) + ")"));
        this.host = host;
        this.port = port;
        this.resolvedAddresses = List.copyOf(resolvedAddresses);
    }

    public static PrivateDestinationBlockedException forLiteral(String host, int port, InetAddress address) {
        return new PrivateDestinationBlockedException(host, port, List.of(address.getHostAddress()));
    }

    public static PrivateDestinationBlockedException forResolved(String host,
                                                                  int port,
                                                                  List<InetSocketAddress> addresses) {
        List<String> values = addresses.stream()
                .map(InetSocketAddress::getAddress)
                .filter(Objects::nonNull)
                .map(InetAddress::getHostAddress)
                .toList();
        return new PrivateDestinationBlockedException(host, port, values);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public List<String> getResolvedAddresses() {
        return resolvedAddresses;
    }
}
