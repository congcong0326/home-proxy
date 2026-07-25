package org.congcong.proxyworker.server;

import java.util.List;

public record ConfigApplyResult(boolean success, List<Long> failedInboundIds, String errorSummary) {
    public static ConfigApplyResult applied() { return new ConfigApplyResult(true, List.of(), null); }
    public static ConfigApplyResult failure(Long inboundId, Throwable error) {
        String message = error == null ? "unknown configuration error" : error.getClass().getSimpleName() + ": " + error.getMessage();
        return new ConfigApplyResult(false, inboundId == null ? List.of() : List.of(inboundId),
                message == null ? "configuration application failed" : message.substring(0, Math.min(1024, message.length())));
    }
}
