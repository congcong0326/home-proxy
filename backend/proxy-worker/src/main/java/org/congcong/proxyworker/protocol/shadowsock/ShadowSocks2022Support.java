package org.congcong.proxyworker.protocol.shadowsock;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.proxyworker.util.ByteBufSplitter;
import org.congcong.proxyworker.util.encryption.algorithm.CryptoProcessor;
import org.congcong.proxyworker.util.encryption.algorithm.HKDF;
import org.congcong.proxyworker.util.encryption.algorithm.NonceUtil;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ShadowSocks2022Support {

    static final byte CLIENT_STREAM_TYPE = 0;
    static final byte SERVER_STREAM_TYPE = 1;
    static final int REQUEST_FIXED_HEADER_SIZE = 11;
    static final int IDENTITY_HEADER_SIZE = 16;
    static final long MAX_TIME_SKEW_SECONDS = 30;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Cache<String, Boolean> SALT_REPLAY_CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(100_000)
            .build();

    private ShadowSocks2022Support() {
    }

    public static boolean isEnabled(ProxyEncAlgo algorithm) {
        return algorithm != null && algorithm.isShadowSocks2022();
    }

    static byte[] randomSalt(int size) {
        byte[] salt = new byte[size];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }

    static void initSessionSubkey(CryptoProcessor processor, byte[] salt) {
        byte[] subkey = HKDF.deriveKey2022(processor.getKey(), salt, processor.getKeySize());
        processor.refreshKey(subkey);
    }

    static byte[] randomPadding(int minLengthInclusive, int maxLengthInclusive) {
        int length = minLengthInclusive;
        if (maxLengthInclusive > minLengthInclusive) {
            length += SECURE_RANDOM.nextInt(maxLengthInclusive - minLengthInclusive + 1);
        }
        byte[] padding = new byte[length];
        SECURE_RANDOM.nextBytes(padding);
        return padding;
    }

    static long currentUnixTimeSeconds() {
        return Instant.now().getEpochSecond();
    }

    static void writeU64BE(ByteBuf out, long value) {
        out.writeLong(value);
    }

    static long readU64BE(byte[] input, int offset) {
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << Byte.SIZE) | (input[offset + i] & 0xFFL);
        }
        return value;
    }

    static void validateTimestamp(long epochSeconds) {
        long diff = Math.abs(currentUnixTimeSeconds() - epochSeconds);
        if (diff > MAX_TIME_SKEW_SECONDS) {
            throw new IllegalArgumentException("Invalid Shadowsocks 2022 timestamp, diff=" + diff + "s");
        }
    }

    static void ensureSaltUnique(byte[] salt) {
        String saltHex = HexFormat.of().formatHex(salt);
        if (SALT_REPLAY_CACHE.asMap().putIfAbsent(saltHex, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Duplicate Shadowsocks 2022 salt detected");
        }
    }

    static byte[] encryptIdentityHeader(byte[] identitySubkey, byte[] plaintext) {
        AESEngine aesEngine = new AESEngine();
        aesEngine.init(true, new KeyParameter(identitySubkey));
        byte[] ciphertext = new byte[plaintext.length];
        aesEngine.processBlock(plaintext, 0, ciphertext, 0);
        return ciphertext;
    }

    static byte[] decryptIdentityHeader(byte[] identitySubkey, byte[] ciphertext) {
        AESEngine aesEngine = new AESEngine();
        aesEngine.init(false, new KeyParameter(identitySubkey));
        byte[] plaintext = new byte[ciphertext.length];
        aesEngine.processBlock(ciphertext, 0, plaintext, 0);
        return plaintext;
    }

    static void writeEncryptedChunk(ByteBuf plaintext, ByteBuf out, CryptoProcessor processor, Counter counter) throws Exception {
        int payloadLength = plaintext.readableBytes();
        byte[] lengthBytes = new byte[] {
                (byte) (payloadLength >>> 8),
                (byte) payloadLength
        };
        out.writeBytes(processor.encrypt(lengthBytes, NonceUtil.generateNonce(counter.next())));

        byte[] payload = new byte[payloadLength];
        plaintext.readBytes(payload);
        out.writeBytes(processor.encrypt(payload, NonceUtil.generateNonce(counter.next())));
    }

    static void writeEncryptedChunks(ByteBuf plaintext, ByteBuf out, CryptoProcessor processor, Counter counter) throws Exception {
        List<ByteBuf> chunks = ByteBufSplitter.splitByteBuf(plaintext);
        for (ByteBuf chunk : chunks) {
            writeEncryptedChunk(chunk, out, processor, counter);
        }
    }

    static byte[] decrypt(CryptoProcessor processor, byte[] ciphertext, Counter counter) throws Exception {
        return processor.decrypt(ciphertext, NonceUtil.generateNonce(counter.next()));
    }

    static ByteBuf encryptedBuffer(ChannelHandlerContext ctx, int estimatedSize) {
        return ctx.alloc().buffer(Math.max(estimatedSize, 256));
    }

    static final class Counter {
        private long value;

        Counter(long initialValue) {
            this.value = initialValue;
        }

        long next() {
            return value++;
        }
    }
}
