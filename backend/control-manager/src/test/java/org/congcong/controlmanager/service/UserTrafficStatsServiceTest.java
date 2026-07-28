package org.congcong.controlmanager.service;

import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
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
        ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        UserTrafficStatsService service = new UserTrafficStatsService(client);

        service.getDailyUserTrafficStats("edge-a", LocalDate.of(2026, 7, 28));

        assertWorkerFilter(client, "edge-a");
    }

    @Test
    void monthlyStatsDefaultBlankWorkerToDefaultNode() {
        ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        UserTrafficStatsService service = new UserTrafficStatsService(client);

        service.getMonthlyUserTrafficStats(" ", YearMonth.of(2026, 7));

        assertWorkerFilter(client, WorkerControlService.DEFAULT_WORKER_ID);
    }

    private void assertWorkerFilter(ClickHouseJdbcClient client, String expectedWorkerId) {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(client).query(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("worker_id = ?");
        assertThat(paramsCaptor.getValue()).hasSize(3);
        assertThat(paramsCaptor.getValue()[0]).isEqualTo(expectedWorkerId);
    }
}
