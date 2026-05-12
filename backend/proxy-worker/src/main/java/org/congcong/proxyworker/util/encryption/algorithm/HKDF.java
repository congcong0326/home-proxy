package org.congcong.proxyworker.util.encryption.algorithm;

import org.bouncycastle.crypto.digests.Blake3Digest;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Blake3Parameters;
import org.bouncycastle.crypto.params.HKDFParameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

public class HKDF {

    private static final byte[] SHADOWSOCKS_SUBKEY_INFO = "ss-subkey".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SHADOWSOCKS_2022_CONTEXT =
            "shadowsocks 2022 session subkey".getBytes(StandardCharsets.UTF_8);

    public static byte[] kdf(String password, int keyLen) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] b = new byte[0];
            byte[] prev = new byte[0];

            while (b.length < keyLen) {
                md5.update(prev);
                md5.update(password.getBytes(StandardCharsets.UTF_8));
                byte[] hash = md5.digest();

                byte[] newB = new byte[b.length + hash.length];
                System.arraycopy(b, 0, newB, 0, b.length);
                System.arraycopy(hash, 0, newB, b.length, hash.length);
                b = newB;

                prev = Arrays.copyOfRange(b, b.length - md5.getDigestLength(), b.length);
                md5.reset();
            }

            return Arrays.copyOf(b, keyLen);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    public static byte[] decodeBase64Key(String encodedKey, int expectedLength) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != expectedLength) {
                throw new IllegalArgumentException("Invalid key length, expected " + expectedLength + " bytes");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Shadowsocks 2022 key, expected Base64 PSK", e);
        }
    }

    public static byte[] deriveKey(byte[] masterKey, byte[] salt, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Key length must be a positive integer.");
        }

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        HKDFParameters params = new HKDFParameters(masterKey, salt, SHADOWSOCKS_SUBKEY_INFO);

        byte[] subkey = new byte[length];
        hkdf.init(params);
        hkdf.generateBytes(subkey, 0, length);
        return subkey;
    }

    public static byte[] deriveKey2022(byte[] masterKey, byte[] salt, int length) {
        return deriveKeyWithBlake3(masterKey, salt, length, SHADOWSOCKS_2022_CONTEXT);
    }

    public static byte[] deriveKeyWithBlake3(byte[] masterKey, byte[] salt, int length, byte[] context) {
        if (length <= 0) {
            throw new IllegalArgumentException("Key length must be a positive integer.");
        }

        Blake3Digest digest = new Blake3Digest();
        digest.init(Blake3Parameters.context(context));
        digest.update(masterKey, 0, masterKey.length);
        digest.update(salt, 0, salt.length);

        byte[] subkey = new byte[length];
        digest.doFinal(subkey, 0, length);
        return subkey;
    }
}
