package org.congcong.proxyworker.dns;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Real DNS view, A first and AAAA only for an empty successful A answer. */
public final class OutboundAddressQuery {
    private OutboundAddressQuery() {}

    public static CompletableFuture<DnsResult> queryAddresses(String host, DnsQueryContext context, EventLoop eventLoop) {
        DnsQueryService service = DnsQueryService.getInstance();
        CompletableFuture<DnsResult> lookup = new CompletableFuture<>();
        CompletableFuture<DnsResult> first = service.query(host, DnsRecordType.A, QueryMode.OUTBOUND_REAL,
                context, eventLoop);
        AtomicReference<CompletableFuture<DnsResult>> active = new AtomicReference<>(first);
        // Cancelling a thenCompose stage does not cancel its source. Propagate cancellation
        // to the active subscription so abandoned DNS work cannot populate the answer cache.
        lookup.whenComplete((ignored, failure) -> {
            if (lookup.isCancelled()) active.get().cancel(false);
        });
        first.whenComplete((answer, failure) -> {
            if (lookup.isCancelled()) return;
            if (failure != null) {
                lookup.completeExceptionally(failure);
            } else if (answer.code() == DnsResponseCode.NOERROR && answer.addresses().isEmpty()) {
                CompletableFuture<DnsResult> next = service.query(host, DnsRecordType.AAAA, QueryMode.OUTBOUND_REAL,
                        context, eventLoop);
                active.set(next);
                if (lookup.isCancelled()) next.cancel(false);
                else next.whenComplete((retried, retryFailure) -> {
                    if (retryFailure == null) lookup.complete(retried);
                    else lookup.completeExceptionally(retryFailure);
                });
            } else {
                lookup.complete(answer);
            }
        });
        return lookup;
    }

}
