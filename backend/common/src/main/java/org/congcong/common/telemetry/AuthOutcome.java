package org.congcong.common.telemetry;

public enum AuthOutcome {
    SUCCESS, FAILURE, DENIED, CHALLENGE, MALFORMED, CANCELLED, ERROR;

    /** Only failed attempts are reported and stored; SUCCESS, CHALLENGE and CANCELLED are not. */
    public boolean isFailure() {
        return this == FAILURE || this == DENIED || this == MALFORMED || this == ERROR;
    }
}
