package org.congcong.controlmanager.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DistributionControllerTest {
    private final DistributionService service = mock(DistributionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DistributionController(service)).build();
    private static final String PATH = "/api/observability/v1/distribution";

    @Test
    void bindsInstantsAndFilters() throws Exception {
        mvc.perform(get(PATH).param("workerId", "home-01").param("from", "2026-10-01T00:00:00Z")
                .param("to", "2026-11-01T00:00:00Z").param("dimension", "targets").param("metric", "bytes")
                .param("limit", "20").param("trafficCategory", "ALL")).andExpect(status().isOk());
        verify(service).aggregate(argThat(query -> query.getDimension().equals("targets") && query.getMetric().equals("bytes")
                && query.getLimit() == 20 && query.getFrom().toString().equals("2026-10-01T00:00:00Z")));
    }

    @Test
    void rejectsInvalidValuesAndReportsUnavailableData() throws Exception {
        mvc.perform(get(PATH).param("from", "invalid")).andExpect(status().isBadRequest());
        when(service.aggregate(any())).thenThrow(new IllegalArgumentException("Invalid scope"));
        mvc.perform(get(PATH)).andExpect(status().isBadRequest());
        doThrow(new IllegalStateException("QUERY_DISABLED")).when(service).aggregate(any());
        mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable());
    }
}
