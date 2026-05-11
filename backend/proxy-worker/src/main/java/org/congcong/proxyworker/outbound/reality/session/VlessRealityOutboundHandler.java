package org.congcong.proxyworker.outbound.reality.session;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;
import java.util.List;
import org.congcong.proxyworker.outbound.reality.tls.Tls13Plaintext;
import org.congcong.proxyworker.outbound.reality.tls.TlsApplicationDataReader;
import org.congcong.proxyworker.outbound.reality.tls.TlsApplicationDataWriter;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecord;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecordDecoder;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecordType;
import org.congcong.proxyworker.outbound.reality.trace.ConnectionTrace;
import org.congcong.proxyworker.outbound.reality.vision.SecureVisionPaddingSource;
import org.congcong.proxyworker.outbound.reality.vision.TlsRecordSniffer;
import org.congcong.proxyworker.outbound.reality.vision.VisionCodec;
import org.congcong.proxyworker.outbound.reality.vision.VisionCommand;
import org.congcong.proxyworker.outbound.reality.vision.VisionDecodeResult;
import org.congcong.proxyworker.outbound.reality.vision.VisionTrafficState;
import org.congcong.proxyworker.outbound.reality.vless.VlessCodec;
import org.congcong.proxyworker.outbound.reality.vless.VlessRequest;
import org.congcong.proxyworker.outbound.reality.vless.VlessResponseHeader;
import org.congcong.proxyworker.outbound.reality.vless.VlessResponseHeaderDecoder;

public final class VlessRealityOutboundHandler extends ChannelDuplexHandler {
    private final VlessRequest request;
    private final ConnectionTrace trace;
    private final TlsApplicationDataWriter tlsWriter;
    private final TlsApplicationDataReader tlsReader;
    private final Promise<Channel> relayPromise;
    private final VlessCodec vlessCodec = new VlessCodec();
    private final VlessResponseHeaderDecoder responseHeaderDecoder = new VlessResponseHeaderDecoder();
    private final RealityPostHandshakeClassifier postHandshakeClassifier = new RealityPostHandshakeClassifier();
    private final VisionTrafficState trafficState = new VisionTrafficState();
    private final TlsRecordSniffer tlsRecordSniffer = new TlsRecordSniffer();
    private final VisionCodec visionCodec;
    private boolean responseHeaderComplete;
    private boolean directDownlink;
    private boolean directUplink;
    private boolean rawUplinkInTls;

    public VlessRealityOutboundHandler(
            VlessRequest request,
            ConnectionTrace trace,
            TlsApplicationDataWriter tlsWriter,
            TlsApplicationDataReader tlsReader,
            Promise<Channel> relayPromise) {
        this.request = request;
        this.trace = trace;
        this.tlsWriter = tlsWriter;
        this.tlsReader = tlsReader;
        this.relayPromise = relayPromise;
        this.visionCodec = new VisionCodec(request.uuid(), new SecureVisionPaddingSource());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if ("HANDSHAKE_COMPLETE".equals(evt)) {
            ctx.write(tlsWriter.encryptApplicationData(vlessCodec.encode(request)));
            ctx.write(tlsWriter.encryptApplicationData(
                    visionCodec.encode(new byte[0], VisionCommand.PADDING_CONTINUE, true))).addListener(future -> {
                if (future.isSuccess()) {
                    if (!relayPromise.isDone()) {
                        relayPromise.setSuccess(ctx.channel());
                    }
                } else if (!relayPromise.isDone()) {
                    relayPromise.setFailure(future.cause());
                }
            });
            ctx.flush();
            trace.event("vless.request.sent", request.host() + ":" + request.port());
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (directDownlink && msg instanceof ByteBuf) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (directDownlink && msg instanceof TlsRecord record) {
            ctx.fireChannelRead(rawRecord(ctx, record));
            return;
        }
        if (msg instanceof TlsRecord record) {
            if (record.type() == TlsRecordType.APPLICATION_DATA && tlsReader != null) {
                handleApplicationData(ctx, record);
                return;
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf payload)) {
            super.write(ctx, msg, promise);
            return;
        }
        byte[] bytes = ByteBufUtil.getBytes(payload, payload.readerIndex(), payload.readableBytes(), true);
        ReferenceCountUtil.release(payload);
        writeUplinkBytes(ctx, bytes, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        trace.event("vless.reality.exception", cause.getClass().getSimpleName() + ":" + cause.getMessage());
        if (!relayPromise.isDone()) {
            relayPromise.setFailure(cause);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!relayPromise.isDone()) {
            relayPromise.setFailure(new IllegalStateException("VLESS REALITY channel closed before ready"));
        }
        super.channelInactive(ctx);
    }

    private void handleApplicationData(ChannelHandlerContext ctx, TlsRecord record) {
        Tls13Plaintext plaintext = tlsReader.decryptApplicationPlaintext(record);
        if (plaintext.contentType() != TlsRecordType.APPLICATION_DATA) {
            trace.event("tls.post_handshake.ignored", plaintext.contentType().name());
            return;
        }
        if (!responseHeaderComplete) {
            RealityPostHandshakeClassifier.Result classification =
                    postHandshakeClassifier.classify(plaintext.payload());
            if (classification == RealityPostHandshakeClassifier.Result.CAMOUFLAGE) {
                trace.event("tls.post_handshake.camouflage", String.valueOf(plaintext.payload().length));
                return;
            }
        }
        VlessResponseHeader response = responseHeaderDecoder.decode(plaintext.payload());
        if (response.complete() && !responseHeaderComplete) {
            responseHeaderComplete = true;
            trace.event("vless.response.header.complete", request.host() + ":" + request.port());
            if (!relayPromise.isDone()) {
                relayPromise.setSuccess(ctx.channel());
            }
        }
        VisionDecodeResult decoded = response.complete()
                ? visionCodec.decode(response.payload())
                : new VisionDecodeResult(new byte[0], false);
        byte[] responsePayload = decoded.payload();
        trafficState.observeDownlink(tlsRecordSniffer.inspect(responsePayload));
        if (responsePayload.length > 0) {
            ctx.fireChannelRead(Unpooled.wrappedBuffer(responsePayload));
        }
        if (decoded.direct()) {
            enableDirectDownlink(ctx);
        }
    }

    private void writeUplinkBytes(ChannelHandlerContext ctx, byte[] bytes, ChannelPromise promise) {
        if (directUplink) {
            trace.event("vision.direct.uplink.raw", String.valueOf(bytes.length));
            ctx.write(Unpooled.wrappedBuffer(bytes), promise);
            return;
        }
        if (rawUplinkInTls) {
            trace.event("vision.padding.uplink.raw", String.valueOf(bytes.length));
            ctx.write(tlsWriter.encryptApplicationData(bytes), promise);
            return;
        }

        TlsRecordSniffer.Result sniff = tlsRecordSniffer.inspect(bytes);
        trafficState.observeUplink(sniff);
        List<byte[]> frames = VisionCodec.reshapeForPadding(bytes);
        boolean directEligibleWrite = sniff.applicationData() && sniff.completeRecord();
        trace.event("local.relay.uplink", String.valueOf(bytes.length));
        for (int i = 0; i < frames.size(); i++) {
            boolean lastFrame = i == frames.size() - 1;
            VisionCommand command = trafficState.commandForUplinkFrame(directEligibleWrite, lastFrame);
            ChannelPromise writePromise = lastFrame ? promise : ctx.voidPromise();
            ctx.write(tlsWriter.encryptApplicationData(
                    visionCodec.encode(frames.get(i), command, sniff.tls())), writePromise);
        }
        if (trafficState.uplinkDirectAfterCurrentWrite()) {
            directUplink = true;
            trafficState.clearUplinkDirectAfterCurrentWrite();
            trace.event("vision.direct.uplink", "enabled");
        } else if (!trafficState.uplinkPaddingActive()) {
            rawUplinkInTls = true;
            trace.event("vision.padding.uplink", "ended");
        }
    }

    private void enableDirectDownlink(ChannelHandlerContext ctx) {
        if (directDownlink) {
            return;
        }
        directDownlink = true;
        if (ctx.pipeline().get(TlsRecordDecoder.class) != null) {
            ctx.pipeline().remove(TlsRecordDecoder.class);
        }
        trace.event("vision.direct.downlink", "enabled");
    }

    private ByteBuf rawRecord(ChannelHandlerContext ctx, TlsRecord record) {
        byte[] payload = record.payload();
        ByteBuf out = ctx.alloc().buffer(5 + payload.length);
        out.writeByte(record.type().code());
        out.writeShort(record.protocolVersion());
        out.writeShort(payload.length);
        out.writeBytes(payload);
        return out;
    }
}
