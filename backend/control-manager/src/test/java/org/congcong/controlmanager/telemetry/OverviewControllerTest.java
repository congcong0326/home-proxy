package org.congcong.controlmanager.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OverviewControllerTest {
    @Test
    void queryOutageReturnsServiceUnavailableForBothEndpointsRatherThanAnAuthenticationFailure() {
        var service = mock(OverviewService.class);
        var controller = new OverviewController(service);
        var query = new OverviewQuery(); query.setWorkerId("edge");
        query.setTo(Instant.now()); query.setFrom(query.getTo().minusSeconds(3600));
        when(service.overview(query)).thenThrow(new IllegalStateException("QUERY_DISABLED"));
        when(service.live(query)).thenThrow(new IllegalStateException("QUERY_DISABLED"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> controller.overview(query)).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> controller.live(query)).getStatusCode());
    }
}
