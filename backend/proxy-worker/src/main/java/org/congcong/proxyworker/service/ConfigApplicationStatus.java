package org.congcong.proxyworker.service;

import java.time.LocalDateTime;

public record ConfigApplicationStatus(String appliedConfigHash, Boolean success, String errorSummary,
                                      LocalDateTime appliedAt) {
    static ConfigApplicationStatus empty() {
        return new ConfigApplicationStatus(null, null, null, null);
    }
}
