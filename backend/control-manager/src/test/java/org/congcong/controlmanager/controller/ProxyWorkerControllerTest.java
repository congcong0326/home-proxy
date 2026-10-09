package org.congcong.controlmanager.controller;

import org.congcong.common.dto.ProxyWorkerDTO;
import org.congcong.common.dto.WorkerTokenResponse;
import org.congcong.common.enums.LogLevel;
import org.congcong.controlmanager.dto.WorkerCreateRequest;
import org.congcong.controlmanager.dto.WorkerUpdateRequest;
import org.congcong.controlmanager.service.AggregateConfigCacheService;
import org.congcong.controlmanager.service.ProxyWorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProxyWorkerControllerTest {
    private ProxyWorkerService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ProxyWorkerService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProxyWorkerController(service, mock(AggregateConfigCacheService.class)))
                .build();
    }

    @Test
    void missingLevelDefaultsToInfoOnCreate() throws Exception {
        when(service.create(any())).thenReturn(new WorkerTokenResponse());
        mvc.perform(post("/api/workers").contentType(MediaType.APPLICATION_JSON).content("""
                {"workerId":"edge-01","displayName":"Edge", "managementUrl":"wss://edge.test/management/ws",
                 "managementToken":"hpw_example-token"}
                """)).andExpect(status().isCreated());
        var request = ArgumentCaptor.forClass(WorkerCreateRequest.class);
        verify(service).create(request.capture());
        assertEquals(LogLevel.INFO, request.getValue().getLogLevel());
    }

    @Test
    void updateAcceptsSupportedLevel() throws Exception {
        when(service.update(eq("edge-01"), any())).thenReturn(new ProxyWorkerDTO());
        mvc.perform(put("/api/workers/edge-01").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"Edge", "logLevel":"WARN"}
                        """)).andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(WorkerUpdateRequest.class);
        verify(service).update(eq("edge-01"), request.capture());
        assertEquals(LogLevel.WARN, request.getValue().getLogLevel());
    }

    @Test
    void rejectsUnsupportedLevelBeforeSaving() throws Exception {
        mvc.perform(put("/api/workers/edge-01").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"Edge", "logLevel":"ALL"}
                        """)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
