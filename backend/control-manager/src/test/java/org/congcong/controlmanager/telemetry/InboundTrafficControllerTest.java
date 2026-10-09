package org.congcong.controlmanager.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InboundTrafficControllerTest {
    private final InboundTrafficService service = mock(InboundTrafficService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InboundTrafficController(service)).build();

    @Test
    void requiresWorkerScopeAndPassesMonthAndTimeZone() throws Exception {
        mvc.perform(get("/api/observability/v1/traffic/inbound/42/month")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(get("/api/observability/v1/traffic/inbound/42/month")
                .param("workerId", "home-01").param("month", "2026-10").param("timeZone", "UTC"))
                .andExpect(status().isOk());
        verify(service).monthly(42, "home-01", "2026-10", "UTC");
    }

    @Test
    void invalidCalendarInputIsBadRequest() throws Exception {
        when(service.monthly(42, "home-01", "invalid", "Asia/Shanghai"))
                .thenThrow(new java.time.DateTimeException("Invalid month"));
        mvc.perform(get("/api/observability/v1/traffic/inbound/42/month")
                .param("workerId", "home-01").param("month", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledOrFailedQueryIsUnavailableRatherThanZero() throws Exception {
        when(service.monthly(42, "home-01", null, "Asia/Shanghai"))
                .thenThrow(new IllegalStateException("QUERY_DISABLED"));
        mvc.perform(get("/api/observability/v1/traffic/inbound/42/month").param("workerId", "home-01"))
                .andExpect(status().isServiceUnavailable());
    }
}
