package org.congcong.proxyworker.outbound.reality.tls;

import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class RealityCryptoContext {

    private final byte[] key;
    private final byte[] iv;
    private long encryptSequence;
    private long decryptSequence;

    public RealityCryptoContext(byte[] key, byte[] iv) {
        this.key = Arrays.copyOf(key, key.length);
        this.iv = Arrays.copyOf(iv, iv.length);
    }

    public byte[] encrypt(byte[] plaintext) {
        return encrypt(plaintext, new byte[0]);
    }

    public byte[] encrypt(byte[] plaintext, byte[] aad) {
        return crypt(Cipher.ENCRYPT_MODE, plaintext, aad, encryptSequence++);
    }

    public byte[] decrypt(byte[] ciphertext) {
        return decrypt(ciphertext, new byte[0]);
    }

    public byte[] decrypt(byte[] ciphertext, byte[] aad) {
        return crypt(Cipher.DECRYPT_MODE, ciphertext, aad, decryptSequence++);
    }

    private byte[] crypt(int mode, byte[] input, byte[] aad, long sequence) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonceFor(sequence)));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to process TLS application data", e);
        }
    }

    private byte[] nonceFor(long value) {
        byte[] nonce = Arrays.copyOf(iv, iv.length);
        byte[] counter = ByteBuffer.allocate(8).putLong(value).array();
        for (int i = 0; i < 8; i++) {
            nonce[nonce.length - 8 + i] ^= counter[i];
        }
        return nonce;
    }
}
