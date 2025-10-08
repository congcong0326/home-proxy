package org.congcong.proxyworker.util.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.proxyworker.util.encryption.algorithm.*;

import java.security.Security;

public class CryptoProcessorFactory {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }



    public static CryptoProcessor createProcessor(ProxyEncAlgo algorithm, String key) {
        CryptoProcessor cryptoProcessor = switch (algorithm) {
            case aes_256_gcm -> new AESGCMProcessor();
            case aes_128_gcm -> new AES128GCMProcessor();
            case chacha20_ietf_poly1305 -> new ChaCha20Poly1305Processor();
            default -> throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
        };
        cryptoProcessor.setKey(HKDF.kdf(key, cryptoProcessor.getKeySize()));
        return cryptoProcessor;
    }

}
