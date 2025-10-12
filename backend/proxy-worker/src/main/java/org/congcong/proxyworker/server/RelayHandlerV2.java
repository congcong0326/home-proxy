//package org.congcong.proxyworker.server;
//
//import io.netty.buffer.ByteBuf;
//import io.netty.buffer.ByteBufHolder;
//import io.netty.buffer.Unpooled;
//import io.netty.channel.Channel;
//import io.netty.channel.ChannelFutureListener;
//import io.netty.channel.ChannelHandlerContext;
//import io.netty.channel.ChannelInboundHandlerAdapter;
//import lombok.extern.slf4j.Slf4j;
//import org.congcong.common.dto.ProxyContext;
//import org.congcong.proxyworker.server.netty.ChannelAttributes;
//
//@Slf4j
//public class RelayHandlerV2 extends ChannelInboundHandlerAdapter  {
//
//    private final Channel relayChannel;
//
//    private final boolean isClient;
//
//    public RelayHandlerV2(Channel relayChannel, boolean isClient) {
//        this.relayChannel = relayChannel;
//        this.isClient = isClient;
//    }
//
//    @Override
//    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//        // 当对端不可写时，别读了
//        if (!relayChannel.isWritable()) {
//            log.info("relay channel can not write");
//            ctx.channel().config().setAutoRead(false);
//            // 添加写入完成监听器，以便在可写时恢复
//            relayChannel.writeAndFlush(msg).addListener(future -> {
//                if (future.isSuccess()) {
//                    log.info("relay channel can write");
//                    ctx.channel().config().setAutoRead(true);
//                }
//            });
//        } else {
//            // 正常转发
//            relayChannel.writeAndFlush(msg);
//        }
//    }
//
//    @Override
//    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//        //有些协议不是长连接，请求一次就关闭了，这个打印并不重要
//        log.debug("{} is inactive, close relay {}", ctx.channel().localAddress(), relayChannel.localAddress());
//        closeOnFlush(relayChannel);
//        ctx.fireChannelInactive();
//    }
//
//    @Override
//    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//        closeOnFlush(relayChannel);
//        closeOnFlush(ctx.channel());
//        ctx.fireExceptionCaught(cause);
//    }
//
//    @Override
//    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
//        // 当本channel不可写时，暂停 relayChannel 的读取
//        relayChannel.config().setAutoRead(ctx.channel().isWritable());  // 停止读取 relayChannel
//        ctx.fireChannelWritabilityChanged();
//    }
//
//    private static void closeOnFlush(Channel ch) {
//        if (ch != null && ch.isOpen()) {
//            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
//        }
//    }
//
//}
//
//
//
