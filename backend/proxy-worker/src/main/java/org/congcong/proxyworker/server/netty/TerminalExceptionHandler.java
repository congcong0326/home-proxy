package org.congcong.proxyworker.server.netty;

import org.congcong.proxyworker.telemetry.TelemetryHooks;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ChannelHandler.Sharable
public class TerminalExceptionHandler extends ChannelInboundHandlerAdapter {

      private TerminalExceptionHandler() {}

      public static TerminalExceptionHandler getInstance() {
          return Holder.INSTANCE;
      }

      private static class Holder {
          private static final TerminalExceptionHandler INSTANCE = new TerminalExceptionHandler();
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          log.info("Closing channel on unhandled exception: {}", cause.toString());
          TelemetryHooks.error(ctx.channel(), "INBOUND", "UNHANDLED_EXCEPTION");
          ctx.close();
      }
}
