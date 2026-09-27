package org.congcong.proxyworker.outbound.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.congcong.proxyworker.security.PrivateDestinationBlockedException;
import org.junit.jupiter.api.Test;

class PublicAddressResolverGroupTest {

    @Test
    void resolvesAllOnceAndReturnsTheFirstPublicAddressInResolverOrder() throws Exception {
        FakeResolverGroup fake = new FakeResolverGroup(List.of(
                resolved("1.1.1.1"), resolved("8.8.8.8")));
        AddressResolver<InetSocketAddress> resolver = new PublicAddressResolverGroup(fake)
                .getResolver(ImmediateEventExecutor.INSTANCE);
        Future<InetSocketAddress> result = resolver.resolve(InetSocketAddress.createUnresolved("public.test", 443));
        result.awaitUninterruptibly();

        assertTrue(result.isSuccess());
        assertEquals("1.1.1.1", result.getNow().getAddress().getHostAddress());
        assertEquals(1, fake.resolveAllCalls.get());
        assertEquals(0, fake.resolveCalls.get());
    }

    @Test
    void rejectsAResolvedPrivateAddress() throws Exception {
        FakeResolverGroup fake = new FakeResolverGroup(List.of(resolved("192.168.10.145")));
        Future<InetSocketAddress> result = new PublicAddressResolverGroup(fake)
                .getResolver(ImmediateEventExecutor.INSTANCE)
                .resolve(InetSocketAddress.createUnresolved("private.test", 22));
        result.awaitUninterruptibly();

        assertFalse(result.isSuccess());
        PrivateDestinationBlockedException error =
                assertInstanceOf(PrivateDestinationBlockedException.class, result.cause());
        assertEquals(List.of("192.168.10.145"), error.getResolvedAddresses());
    }

    @Test
    void rejectsMixedPublicAndPrivateResultsAsOneUnsafeAnswerSet() throws Exception {
        FakeResolverGroup fake = new FakeResolverGroup(List.of(resolved("8.8.8.8"), resolved("127.0.0.1")));
        Future<List<InetSocketAddress>> result = new PublicAddressResolverGroup(fake)
                .getResolver(ImmediateEventExecutor.INSTANCE)
                .resolveAll(InetSocketAddress.createUnresolved("mixed.test", 443));
        result.awaitUninterruptibly();

        assertFalse(result.isSuccess());
        assertInstanceOf(PrivateDestinationBlockedException.class, result.cause());
        assertEquals(1, fake.resolveAllCalls.get());
    }

    @Test
    void preservesAnEmptyOrFailedResolverResultAsANetworkResolutionFailure() {
        FakeResolverGroup fake = new FakeResolverGroup(List.of());
        Future<InetSocketAddress> result = new PublicAddressResolverGroup(fake)
                .getResolver(ImmediateEventExecutor.INSTANCE)
                .resolve(InetSocketAddress.createUnresolved("empty.test", 443));
        result.awaitUninterruptibly();

        assertFalse(result.isSuccess());
        assertFalse(result.cause() instanceof PrivateDestinationBlockedException);
    }

    private static InetSocketAddress resolved(String ip) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(ip), 443);
    }

    private static final class FakeResolverGroup extends AddressResolverGroup<InetSocketAddress> {
        private final List<InetSocketAddress> answers;
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicInteger resolveAllCalls = new AtomicInteger();

        private FakeResolverGroup(List<InetSocketAddress> answers) {
            this.answers = answers;
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                @Override
                protected boolean doIsResolved(InetSocketAddress address) {
                    return false;
                }

                @Override
                protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                    resolveCalls.incrementAndGet();
                    if (answers.isEmpty()) {
                        promise.setFailure(new IllegalStateException("no result"));
                    } else {
                        promise.setSuccess(answers.get(0));
                    }
                }

                @Override
                protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                    resolveAllCalls.incrementAndGet();
                    promise.setSuccess(answers);
                }
            };
        }
    }
}
