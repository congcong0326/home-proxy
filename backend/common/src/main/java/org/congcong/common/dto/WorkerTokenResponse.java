package org.congcong.common.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Registration response retained for API compatibility; the controller never generates a Worker token. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkerTokenResponse {
    private ProxyWorkerDTO worker;
    /** Always absent for controller-initiated management connections. */
    private String token;
}
