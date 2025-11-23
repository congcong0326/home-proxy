package org.congcong.proxyworker.server.netty.tls;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLException;

public class TlsClientContextManager {
      private static final TlsClientContextManager INSTANCE = new TlsClientContextManager();
      private final SslContext clientCtx;

      private TlsClientContextManager() {
          try {
              this.clientCtx = SslContextBuilder.forClient()
                      .protocols("TLSv1.3", "TLSv1.2")
                      // TODO: 换成真实 CA；开发期可先用 InsecureTrustManagerFactory.INSTANCE
                      .trustManager(InsecureTrustManagerFactory.INSTANCE)
                      .build();
          } catch (SSLException e) {
              throw new IllegalStateException("init client SslContext failed", e);
          }
      }
      public static TlsClientContextManager getInstance() { return INSTANCE; }
      public SslContext getClientContext() { return clientCtx; }
  }