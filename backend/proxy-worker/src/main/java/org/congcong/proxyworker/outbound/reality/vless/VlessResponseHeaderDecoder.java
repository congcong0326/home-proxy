package org.congcong.proxyworker.outbound.reality.vless;

public final class VlessResponseHeaderDecoder {
    private static final int VERSION = 0;

    private enum State {
        VERSION,
        ADDONS_LENGTH,
        ADDONS,
        COMPLETE
    }

    private State state = State.VERSION;
    private int addonsRemaining;

    public VlessResponseHeader decode(byte[] input) {
        int index = 0;
        while (index < input.length && state != State.COMPLETE) {
            if (state == State.VERSION) {
                int version = input[index] & 0xff;
                if (version != VERSION) {
                    throw new IllegalArgumentException("Unexpected VLESS response version: " + version);
                }
                index++;
                state = State.ADDONS_LENGTH;
            } else if (state == State.ADDONS_LENGTH) {
                addonsRemaining = input[index] & 0xff;
                index++;
                state = addonsRemaining == 0 ? State.COMPLETE : State.ADDONS;
            } else if (state == State.ADDONS) {
                int skipped = Math.min(addonsRemaining, input.length - index);
                index += skipped;
                addonsRemaining -= skipped;
                if (addonsRemaining == 0) {
                    state = State.COMPLETE;
                }
            }
        }

        byte[] payload = new byte[state == State.COMPLETE ? input.length - index : 0];
        if (payload.length > 0) {
            System.arraycopy(input, index, payload, 0, payload.length);
        }
        return new VlessResponseHeader(state == State.COMPLETE, payload);
    }
}
