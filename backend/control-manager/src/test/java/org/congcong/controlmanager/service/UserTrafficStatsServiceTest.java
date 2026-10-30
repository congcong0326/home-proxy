package org.congcong.controlmanager.service;

import org.congcong.controlmanager.telemetry.TelemetryJdbcClient;
import org.congcong.controlmanager.telemetry.TelemetryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserTrafficStatsServiceTest {

    @Test
    void dailyStatsFilterByWorker() {
        TelemetryJdbcClient client = mock(TelemetryJdbcClient.class);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        UserTrafficStatsService service = new UserTrafficStatsService(client, mock(TelemetryStore.class));

        service.getDailyUserTrafficStats("edge-a", LocalDate.of(2026, 7, 28));

        assertWorkerFilter(client, "edge-a");
    }

    @Test
    void monthlyStatsDefaultBlankWorkerToDefaultNode() {
        TelemetryJdbcClient client = mock(TelemetryJdbcClient.class);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        UserTrafficStatsService service = new UserTrafficStatsService(client, mock(TelemetryStore.class));

        service.getMonthlyUserTrafficStats(" ", YearMonth.of(2026, 7));

        assertWorkerFilter(client, WorkerControlService.DEFAULT_WORKER_ID);
    }

    private void assertWorkerFilter(TelemetryJdbcClient client, String expectedWorkerId) {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(client).query(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("worker_id = ?");
        assertThat(paramsCaptor.getValue()).hasSize(3);
        assertThat(paramsCaptor.getValue()[0]).isEqualTo(expectedWorkerId);
    }
}
