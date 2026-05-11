package org.congcong.proxyworker.outbound.reality.tls;

import org.congcong.proxyworker.outbound.reality.config.RealityClientConfig;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

public final class RealityClientHelloFactory {

    private final SecureRandom secureRandom = new SecureRandom();
    private final TlsExtensionWriter extensionWriter = new TlsExtensionWriter();

    public byte[] create(RealityClientConfig config) {
        return createSession(config).payload();
    }

    public RealityClientHello createSession(RealityClientConfig config) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        byte[] sessionId = new byte[32];
        X25519PrivateKeyParameters clientPrivateKey = new X25519PrivateKeyParameters(secureRandom);
        byte[] clientPublicKey = new byte[32];
        clientPrivateKey.generatePublicKey().encode(clientPublicKey, 0);

        byte[] extensions = extensionWriter.extensionBlock(
                extensionWriter.signatureAlgorithms(),
                extensionWriter.applicationLayerProtocolNegotiation(),
                extensionWriter.supportedVersions(),
                extensionWriter.serverName(config.serverName()),
                extensionWriter.supportedGroups(),
                extensionWriter.keyShareX25519(clientPublicKey),
                extensionWriter.pskKeyExchangeModes());

        ByteBuffer body = ByteBuffer.allocate(512);
        body.put((byte) 0x01);
        body.put(new byte[] {0x00, 0x00, 0x00});
        body.putShort((short) 0x0303);
        body.put(random);
        body.put((byte) 0x20);
        body.put(sessionId);
        body.putShort((short) 0x0002);
        body.putShort((short) 0x1301);
        body.put((byte) 0x01);
        body.put((byte) 0x00);
        body.put(extensions);

        int length = body.position() - 4;
        body.put(1, (byte) ((length >>> 16) & 0xff));
        body.put(2, (byte) ((length >>> 8) & 0xff));
        body.put(3, (byte) (length & 0xff));

        byte[] payload = new byte[body.position()];
        body.flip();
        body.get(payload);
        RealitySessionMaterial material = buildRealitySessionId(config, random, clientPrivateKey, payload);
        System.arraycopy(material.encryptedSessionId, 0, payload, 39, material.encryptedSessionId.length);
        return new RealityClientHello(payload, clientPrivateKey, material.authKey);
    }

    private RealitySessionMaterial buildRealitySessionId(
            RealityClientConfig config,
            byte[] random,
            X25519PrivateKeyParameters clientPrivateKey,
            byte[] payload) {
        try {
            byte[] sessionPlaintext = new byte[16];
            sessionPlaintext[0] = 26;
            sessionPlaintext[1] = 5;
            sessionPlaintext[2] = 3;
            sessionPlaintext[3] = 0x00;
            int unixTime = (int) (System.currentTimeMillis() / 1000L);
            ByteBuffer.wrap(sessionPlaintext, 4, 4).putInt(unixTime);
            byte[] shortId = RealityParameters.from(config).shortIdBytes();
            System.arraycopy(shortId, 0, sessionPlaintext, 8, Math.min(shortId.length, 8));

            byte[] sharedSecret = new byte[32];
            X25519PublicKeyParameters serverPublicKey = new X25519PublicKeyParameters(
                    decodeRawUrlBase64(config.publicKey()), 0);
            clientPrivateKey.generateSecret(serverPublicKey, sharedSecret, 0);

            byte[] authKey = hkdfSha256(sharedSecret, Arrays.copyOf(random, 20), "REALITY".getBytes(StandardCharsets.US_ASCII), 32);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(authKey, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(random, 20, 32)));
            cipher.updateAAD(payload);
            return new RealitySessionMaterial(cipher.doFinal(sessionPlaintext), authKey);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build REALITY session id", e);
        }
    }

    private static final class RealitySessionMaterial {
        private final byte[] encryptedSessionId;
        private final byte[] authKey;

        private RealitySessionMaterial(byte[] encryptedSessionId, byte[] authKey) {
            this.encryptedSessionId = encryptedSessionId;
            this.authKey = authKey;
        }
    }

    private byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        byte[] prk = hmacSha256(salt, ikm);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        byte counter = 1;
        while (out.size() < length) {
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            block.write(previous);
            block.write(info);
            block.write(counter);
            previous = hmacSha256(prk, block.toByteArray());
            out.write(previous);
            counter++;
        }
        byte[] okm = out.toByteArray();
        return Arrays.copyOf(okm, length);
    }

    private byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private byte[] decodeRawUrlBase64(String value) {
        int padding = (4 - (value.length() % 4)) % 4;
        StringBuilder builder = new StringBuilder(value);
        for (int i = 0; i < padding; i++) {
            builder.append('=');
        }
        return Base64.getUrlDecoder().decode(builder.toString());
    }
}
