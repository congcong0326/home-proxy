package org.congcong.proxyworker.telemetry;

import io.netty.channel.Channel;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import static org.congcong.proxyworker.telemetry.SessionObservation.Stage.DNS;

/** Per-connect observer. Closing it never closes a shared/default/security resolver. */
final class TimingAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {
    private final AddressResolverGroup<?> delegateGroup;
    private final Channel inbound;

    TimingAddressResolverGroup(AddressResolverGroup<?> delegateGroup, Channel inbound) {
        this.delegateGroup = delegateGroup;
        this.inbound = inbound;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        AddressResolver<InetSocketAddress> delegate = (AddressResolver<InetSocketAddress>) delegateGroup.getResolver(executor);
        return new AddressResolver<>() {
            public boolean isSupported(SocketAddress address) { return delegate.isSupported(address); }
            public boolean isResolved(SocketAddress address) { return delegate.isResolved(address); }
            public Future<InetSocketAddress> resolve(SocketAddress address) { return resolve(address, executor.newPromise()); }
            public Future<InetSocketAddress> resolve(SocketAddress address, Promise<InetSocketAddress> promise) {
                observe(address, promise);
                return delegate.resolve(address, promise);
            }
            public Future<List<InetSocketAddress>> resolveAll(SocketAddress address) { return resolveAll(address, executor.newPromise()); }
            public Future<List<InetSocketAddress>> resolveAll(SocketAddress address, Promise<List<InetSocketAddress>> promise) {
                observe(address, promise);
                return delegate.resolveAll(address, promise);
            }
            private void observe(SocketAddress address, Promise<?> promise) {
                if (address instanceof InetSocketAddress endpoint && endpoint.isUnresolved()) {
                    TelemetryHooks.beginStage(inbound, DNS);
                    promise.addListener(done -> TelemetryHooks.endStage(inbound, DNS));
                }
            }
            public void close() { /* delegate belongs to its original group */ }
        };
    }
}
