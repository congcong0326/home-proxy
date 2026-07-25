package org.congcong.common.dto;

import lombok.Data;

/** This response is intentionally used only by create/token endpoints. */
@Data
public class WorkerTokenResponse {
    private ProxyWorkerDTO worker;
    private String token;
}
