package org.congcong.proxyworker.server;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.proxyworker.audit.AccessLogUtil;
import org.congcong.proxyworker.server.netty.ChannelAttributes;


/**
 * 中继处理器
 * 将这个处理器注册到某个channel的pipeline，当io事件发生时，传递给其他channel
 */
@Slf4j
public class RelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel relayChannel;

    private final boolean isClient;

    public RelayHandler(Channel relayChannel, boolean isClient) {
        this.relayChannel = relayChannel;
        this.isClient = isClient;
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

        // 计算本次转发的字节数（仅当消息为 ByteBuf 或持有 ByteBuf 的对象）
        final int bytes = readableBytes(msg);

        // 写到对端，失败则关闭双端；成功时累加字节统计
        relayChannel.write(msg).addListener(future -> {
            if (!future.isSuccess()) {
                Throwable cause = future.cause();
                String errorMsg = cause != null ? cause.getMessage() : "unknown (maybe channel closed)";
                log.warn("Relaying write failed: {}", errorMsg);
                Channel contextChannel = isClient ? ctx.channel() : relayChannel;
                if (contextChannel != null) {
                    AccessLogUtil.logFailure(contextChannel, 500, "WRITE_ERROR", errorMsg);
                }
                closeOnFlush(ctx.channel());
                closeOnFlush(relayChannel);
            } else if (bytes > 0) {
                // 依据方向选择绑定了上下文的通道：
                // isClient=true 表示处理客户端->代理的流，上下文在入站通道（ctx.channel）
                // isClient=false 表示处理服务端->代理的流，上下文在入站通道（relayChannel）
                Channel contextChannel = isClient ? ctx.channel() : relayChannel;
                ProxyContext proxyContext = ChannelAttributes.getProxyContext(contextChannel);
                if (proxyContext == null) {
                    // 作为兜底，再尝试另一侧通道，避免上下文缺失
                    proxyContext = ChannelAttributes.getProxyContext(isClient ? relayChannel : ctx.channel());
                }
                if (proxyContext != null) {
                    if (isClient) {
                        // 来自客户端的数据（client -> proxy -> server）累加到 bytesIn
                        proxyContext.setBytesIn(proxyContext.getBytesIn() + bytes);
                    } else {
                        // 来自服务端的数据（server -> proxy -> client）累加到 bytesOut
                        proxyContext.setBytesOut(proxyContext.getBytesOut() + bytes);
                    }
                }
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
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception{
        // log.debug("Exception in RelayHandler: {}", cause.getMessage(), cause);
        // 增加访问日志记录
        Channel contextChannel = isClient ? ctx.channel() : relayChannel;
        if (contextChannel != null) {
            AccessLogUtil.logFailure(contextChannel, 500, "RELAY_ERROR", cause.getMessage());
        }
        closeOnFlush(ctx.channel());
        closeOnFlush(relayChannel);
        //super.exceptionCaught(ctx, cause);
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

    private static int readableBytes(Object msg) {
        if (msg instanceof ByteBuf buf) {
            return buf.readableBytes();
        }
        if (msg instanceof ByteBufHolder holder) {
            ByteBuf content = holder.content();
            return content != null ? content.readableBytes() : 0;
        }
        return 0;
    }
}
