package org.congcong.controlmanager.logstore;

import org.congcong.controlmanager.clickhouse.ClickHouseAccessLogWriter;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClickHouseAccessLogStoreTest {

    @Test
    void globalTrafficTrendFiltersByWorker() {
        ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
        ClickHouseAccessLogWriter writer = mock(ClickHouseAccessLogWriter.class);
        ClickHouseAccessLogStore store = new ClickHouseAccessLogStore(writer, client);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        store.getGlobalTrafficTrend(
                "edge-a",
                LocalDateTime.of(2026, 7, 28, 10, 0),
                LocalDateTime.of(2026, 7, 28, 11, 0));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(client).query(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("worker_id = ?");
        assertThat(paramsCaptor.getValue()).hasSize(3);
        assertThat(paramsCaptor.getValue()[0]).isEqualTo("edge-a");
    }

    @Test
    void userTrafficTrendFiltersByWorkerAndUser() {
        ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
        ClickHouseAccessLogWriter writer = mock(ClickHouseAccessLogWriter.class);
        ClickHouseAccessLogStore store = new ClickHouseAccessLogStore(writer, client);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        store.getUserTrafficTrend(
                "edge-b",
                42L,
                LocalDateTime.of(2026, 7, 28, 10, 0),
                LocalDateTime.of(2026, 7, 28, 11, 0));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(client).query(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("worker_id = ?", "user_id = ?");
        assertThat(paramsCaptor.getValue()).hasSize(4);
        assertThat(paramsCaptor.getValue()[0]).isEqualTo("edge-b");
        assertThat(paramsCaptor.getValue()[1]).isEqualTo(42L);
    }

}
