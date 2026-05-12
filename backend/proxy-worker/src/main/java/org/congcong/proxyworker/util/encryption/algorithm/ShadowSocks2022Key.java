package org.congcong.proxyworker.util.encryption.algorithm;

import org.bouncycastle.crypto.digests.Blake3Digest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ShadowSocks2022Key {

    private static final byte[] SHADOWSOCKS_2022_IDENTITY_CONTEXT =
            "shadowsocks 2022 identity subkey".getBytes();

    private ShadowSocks2022Key() {
    }

    public static List<byte[]> decodeKeyChain(String encodedKeys, int expectedLength) {
        if (encodedKeys == null || encodedKeys.isBlank()) {
            throw new IllegalArgumentException("Missing Shadowsocks 2022 PSK");
        }
        String[] parts = encodedKeys.split(":");
        List<byte[]> keys = new ArrayList<>(parts.length);
        for (String part : parts) {
            String key = part.trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Invalid Shadowsocks 2022 key chain");
            }
            keys.add(HKDF.decodeBase64Key(key, expectedLength));
        }
        return Collections.unmodifiableList(keys);
    }

    public static byte[] decodeUserKey(String encodedKeys, int expectedLength) {
        List<byte[]> keyChain = decodeKeyChain(encodedKeys, expectedLength);
        return keyChain.get(keyChain.size() - 1);
    }

    public static List<byte[]> decodeIdentityKeys(String encodedKeys, int expectedLength) {
        List<byte[]> keyChain = decodeKeyChain(encodedKeys, expectedLength);
        if (keyChain.size() <= 1) {
            return List.of();
        }
        return keyChain.subList(0, keyChain.size() - 1);
    }

    public static boolean hasIdentityHeader(String encodedKeys) {
        return encodedKeys != null && encodedKeys.contains(":");
    }

    public static byte[] deriveIdentitySubkey(byte[] identityKey, byte[] salt) {
        return HKDF.deriveKeyWithBlake3(identityKey, salt, identityKey.length, SHADOWSOCKS_2022_IDENTITY_CONTEXT);
    }

    public static byte[] hashForIdentityHeader(byte[] nextKey) {
        Blake3Digest digest = new Blake3Digest();
        digest.update(nextKey, 0, nextKey.length);
        byte[] hash = new byte[16];
        digest.doFinal(hash, 0, hash.length);
        return hash;
    }
}
