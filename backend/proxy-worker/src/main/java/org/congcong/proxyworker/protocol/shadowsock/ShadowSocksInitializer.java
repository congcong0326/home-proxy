package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.proxyworker.config.FindUser;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.config.UserQueryService;
import org.congcong.proxyworker.server.netty.AbstractChannelInitializer;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.util.encryption.CryptoProcessorFactory;

import java.util.List;

@Slf4j
public class ShadowSocksInitializer extends AbstractChannelInitializer {

    public ShadowSocksInitializer(InboundConfig inboundConfig) {
        super(inboundConfig);
    }

    @Override
    protected void init(Channel socketChannel) {
        UserConfig userConfig = FindUser.find(null, inboundConfig);
        ProxyEncAlgo ssMethod = inboundConfig.getSsMethod();
        String credential = userConfig.getCredential();
        ChannelAttributes.setAuthenticatedUser(socketChannel, userConfig);
        socketChannel.pipeline().addLast(
                // 解密数据
                new DecryptedSocksHandler(CryptoProcessorFactory.createProcessor(ssMethod, credential)),
                new ShadowSocksHandler(),
                // 加密数据
                new EncryptedSocksHandler(CryptoProcessorFactory.createProcessor(ssMethod, credential)));
    }
}
