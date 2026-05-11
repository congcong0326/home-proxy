package org.congcong.proxyworker.outbound.reality.vision;

public final class VisionTrafficState {
    private int packetsToFilter = 8;
    private boolean tls;
    private boolean tls13;
    private boolean uplinkPadding = true;
    private boolean uplinkDirectAfterCurrentWrite;

    public void observeUplink(TlsRecordSniffer.Result result) {
        observe(result);
    }

    public void observeDownlink(TlsRecordSniffer.Result result) {
        observe(result);
    }

    public VisionCommand commandForUplink(TlsRecordSniffer.Result result, boolean lastFrame) {
        observeUplink(result);
        return commandForUplinkFrame(result.applicationData() && result.completeRecord(), lastFrame);
    }

    public VisionCommand commandForUplinkFrame(boolean directEligibleWrite, boolean lastFrame) {
        if (!uplinkPadding) {
            return VisionCommand.PADDING_END;
        }
        if (tls && tls13 && directEligibleWrite && lastFrame) {
            uplinkPadding = false;
            uplinkDirectAfterCurrentWrite = true;
            return VisionCommand.PADDING_DIRECT;
        }
        if (!tls13 && packetsToFilter <= 1) {
            uplinkPadding = false;
            return VisionCommand.PADDING_END;
        }
        return VisionCommand.PADDING_CONTINUE;
    }

    public boolean uplinkDirectAfterCurrentWrite() {
        return uplinkDirectAfterCurrentWrite;
    }

    public boolean uplinkPaddingActive() {
        return uplinkPadding;
    }

    public void clearUplinkDirectAfterCurrentWrite() {
        uplinkDirectAfterCurrentWrite = false;
    }

    public void consumePacketBudgetUntilEnd() {
        packetsToFilter = 1;
    }

    private void observe(TlsRecordSniffer.Result result) {
        if (packetsToFilter > 0) {
            packetsToFilter--;
        }
        if (result.tls()) {
            tls = true;
        }
        if (result.tls13()) {
            tls13 = true;
        }
    }
}
