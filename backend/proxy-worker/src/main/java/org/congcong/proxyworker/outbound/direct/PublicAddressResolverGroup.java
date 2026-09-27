package org.congcong.proxyworker.outbound.direct;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.security.DestinationAddressPolicy;
import org.congcong.proxyworker.security.PrivateDestinationBlockedException;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolves a direct destination once, rejects any non-public answer, and
 * passes the same resolved address to Netty's connect operation.
 */
public final class PublicAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    public static final PublicAddressResolverGroup INSTANCE =
            new PublicAddressResolverGroup(DefaultAddressResolverGroup.INSTANCE);

    private final AddressResolverGroup<InetSocketAddress> delegateGroup;

    /**
     * Public for deterministic tests that need a fake resolver. The supplied
     * group remains responsible for resolver lifecycle just as Netty's
     * default group does.
     */
    public PublicAddressResolverGroup(AddressResolverGroup<InetSocketAddress> delegateGroup) {
        this.delegateGroup = delegateGroup;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new PublicAddressResolver(delegateGroup.getResolver(executor));
    }

    private static final class PublicAddressResolver implements AddressResolver<InetSocketAddress> {
        private final AddressResolver<InetSocketAddress> delegate;

        private PublicAddressResolver(AddressResolver<InetSocketAddress> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isSupported(SocketAddress address) {
            return delegate.isSupported(address);
        }

        @Override
        public boolean isResolved(SocketAddress address) {
            if (!(address instanceof InetSocketAddress inetSocketAddress)) {
                return delegate.isResolved(address);
            }
            // A resolved private address must still flow through validateAll.
            return !inetSocketAddress.isUnresolved()
                    && DestinationAddressPolicy.isPubliclyRoutable(inetSocketAddress.getAddress());
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address) {
            return resolve(address, newPromise(address));
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address, Promise<InetSocketAddress> promise) {
            if (!(address instanceof InetSocketAddress inetSocketAddress)) {
                return delegate.resolve(address, promise);
            }
            delegate.resolveAll(address).addListener(future -> {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                    return;
                }
                @SuppressWarnings("unchecked")
                List<InetSocketAddress> addresses = (List<InetSocketAddress>) future.getNow();
                Throwable validationFailure = validateAll(inetSocketAddress, addresses);
                if (validationFailure != null) {
                    promise.tryFailure(validationFailure);
                    return;
                }
                promise.trySuccess(addresses.get(0));
            });
            return promise;
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(SocketAddress address) {
            return resolveAll(address, newListPromise(address));
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(SocketAddress address,
                                                           Promise<List<InetSocketAddress>> promise) {
            if (!(address instanceof InetSocketAddress inetSocketAddress)) {
                return delegate.resolveAll(address, promise);
            }
            delegate.resolveAll(address).addListener(future -> {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                    return;
                }
                @SuppressWarnings("unchecked")
                List<InetSocketAddress> addresses = (List<InetSocketAddress>) future.getNow();
                Throwable validationFailure = validateAll(inetSocketAddress, addresses);
                if (validationFailure != null) {
                    promise.tryFailure(validationFailure);
                    return;
                }
                promise.trySuccess(List.copyOf(addresses));
            });
            return promise;
        }

        @Override
        public void close() {
            delegate.close();
        }

        private Promise<InetSocketAddress> newPromise(SocketAddress address) {
            // Netty always calls the promise overload from Bootstrap. This
            // fallback keeps the AddressResolver contract intact for callers
            // that invoke resolve(address) directly.
            return io.netty.util.concurrent.GlobalEventExecutor.INSTANCE.newPromise();
        }

        private Promise<List<InetSocketAddress>> newListPromise(SocketAddress address) {
            return io.netty.util.concurrent.GlobalEventExecutor.INSTANCE.newPromise();
        }

        private Throwable validateAll(InetSocketAddress requested, List<InetSocketAddress> addresses) {
            if (addresses == null || addresses.isEmpty()) {
                return new UnknownHostException("resolver returned no addresses for " + requested.getHostString());
            }
            for (InetSocketAddress address : addresses) {
                if (address == null || address.isUnresolved() || address.getAddress() == null) {
                    return new UnknownHostException("resolver returned an unresolved address for " + requested.getHostString());
                }
                if (!DestinationAddressPolicy.isPubliclyRoutable(address.getAddress())) {
                    return PrivateDestinationBlockedException.forResolved(
                            requested.getHostString(), requested.getPort(), addresses);
                }
            }
            return null;
        }
    }
}
