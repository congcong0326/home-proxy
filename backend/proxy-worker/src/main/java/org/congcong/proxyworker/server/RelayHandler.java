package org.congcong.proxyworker.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;


/**
 * 中继处理器
 * 将这个处理器注册到某个channel的pipeline，当io事件发生时，传递给其他channel
 */
@Slf4j
public class RelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel relayChannel;

    public RelayHandler(Channel relayChannel) {
        this.relayChannel = relayChannel;
    }

    /**
     * client --> (auto read)channel ---> channel (write) ---> server
     * client <-- (write)channel <--- channel (auto read)<---server
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (relayChannel == null || !relayChannel.isActive()) {
            ReferenceCountUtil.release(msg);
            closeOnFlush(ctx.channel());
            return;
        }

        // 当对端不可写时，对当前入站禁用自动读取，形成背压
        if (!relayChannel.isWritable()) {
            ctx.channel().config().setAutoRead(false);
        }

        // 写到对端，失败则关闭双端
        relayChannel.write(msg).addListener(future -> {
            if (!future.isSuccess()) {
                Throwable cause = future.cause();
                log.warn("Relaying write failed: {}", cause == null ? "unknown" : cause.getMessage());
                closeOnFlush(ctx.channel());
                closeOnFlush(relayChannel);
            }
        });
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (relayChannel != null && relayChannel.isActive()) {
            relayChannel.flush();
        }
        super.channelReadComplete(ctx);
    }

    //对端关闭，则一起关闭
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closeOnFlush(relayChannel);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Exception in RelayHandler: {}", cause.getMessage(), cause);
        closeOnFlush(ctx.channel());
        closeOnFlush(relayChannel);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        // 当前channel可写性变化时，调整对端的自动读取以实现背压
        if (relayChannel != null && relayChannel.isActive()) {
            boolean writable = ctx.channel().isWritable();
            relayChannel.config().setAutoRead(writable);
            if (writable) {
                // 重新触发一次读取以恢复数据流
                relayChannel.read();
            }
        }
        super.channelWritabilityChanged(ctx);
    }

    private static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isOpen()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
