package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
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
    protected void init(SocketChannel socketChannel) {
        List<UserConfig> allowedUsers = inboundConfig.getAllowedUsers();
        if(allowedUsers.size() > 1) {
            log.warn("ShadowSocks {} must have only one user, now is {}", inboundConfig.getName(), allowedUsers.size());
        }
        UserConfig userConfig = allowedUsers.get(0);
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
