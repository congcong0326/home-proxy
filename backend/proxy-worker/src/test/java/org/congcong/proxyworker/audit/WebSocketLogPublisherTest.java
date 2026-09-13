package org.congcong.proxyworker.audit;

import org.congcong.common.dto.AccessLog;
import org.congcong.proxyworker.audit.impl.WebSocketLogPublisher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketLogPublisherTest {
    @Test
    void drainsAndRequeuesAccessLogsWithoutHttpTransport() {
        WebSocketLogPublisher publisher = new WebSocketLogPublisher(2);
        AccessLog first = new AccessLog();
        AccessLog second = new AccessLog();
        publisher.publishAccess(first);
        publisher.publishAccess(second);

        assertEquals(1, publisher.drainAccess(1).size());
        publisher.requeueAccess(java.util.List.of(first));
        assertEquals(2, publisher.drainAccess(10).size());
        publisher.shutdown();
    }
}
