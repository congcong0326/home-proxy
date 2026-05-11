package org.congcong.proxyworker.outbound.reality.vision;

public enum VisionCommand {
    PADDING_CONTINUE(0),
    PADDING_END(1),
    PADDING_DIRECT(2);

    private final int code;

    VisionCommand(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static VisionCommand fromCode(int code) {
        for (VisionCommand command : values()) {
            if (command.code == code) {
                return command;
            }
        }
        throw new IllegalArgumentException("Unknown Vision command: " + code);
    }
}
