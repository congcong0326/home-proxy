package org.congcong.proxyworker.outbound.reality.tls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Tls13KeySchedule {

    private static final int HASH_LENGTH = 32;

    public RealityCryptoContext serverHandshakeTraffic(byte[] sharedSecret, byte[] transcript) {
        return handshakeTraffic(sharedSecret, transcript).serverHandshakeContext();
    }

    public HandshakeSecrets handshakeTraffic(byte[] sharedSecret, byte[] transcript) {
        try {
            byte[] zero = new byte[HASH_LENGTH];
            byte[] earlySecret = hkdfExtract(zero, zero);
            byte[] derivedSecret = deriveSecret(earlySecret, "derived", new byte[0]);
            byte[] handshakeSecret = hkdfExtract(derivedSecret, sharedSecret);
            byte[] clientHandshakeSecret = deriveSecret(handshakeSecret, "c hs traffic", transcript);
            byte[] serverHandshakeSecret = deriveSecret(handshakeSecret, "s hs traffic", transcript);
            return new HandshakeSecrets(
                    this,
                    handshakeSecret,
                    clientHandshakeSecret,
                    serverHandshakeSecret,
                    trafficContext(clientHandshakeSecret),
                    trafficContext(serverHandshakeSecret));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive TLS 1.3 handshake traffic keys", e);
        }
    }

    private ApplicationSecrets applicationTraffic(byte[] handshakeSecret, byte[] transcript) {
        try {
            byte[] derivedSecret = deriveSecret(handshakeSecret, "derived", new byte[0]);
            byte[] masterSecret = hkdfExtract(derivedSecret, new byte[HASH_LENGTH]);
            byte[] clientApplicationSecret = deriveSecret(masterSecret, "c ap traffic", transcript);
            byte[] serverApplicationSecret = deriveSecret(masterSecret, "s ap traffic", transcript);
            return new ApplicationSecrets(
                    trafficContext(clientApplicationSecret),
                    trafficContext(serverApplicationSecret));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive TLS 1.3 application traffic keys", e);
        }
    }

    private RealityCryptoContext trafficContext(byte[] trafficSecret) throws Exception {
        byte[] key = hkdfExpandLabel(trafficSecret, "key", new byte[0], 16);
        byte[] iv = hkdfExpandLabel(trafficSecret, "iv", new byte[0], 12);
        return new RealityCryptoContext(key, iv);
    }

    private byte[] deriveSecret(byte[] secret, String label, byte[] transcript) throws Exception {
        return hkdfExpandLabel(secret, label, sha256(transcript), HASH_LENGTH);
    }

    private byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        return hmacSha256(salt, ikm);
    }

    private byte[] hkdfExpandLabel(byte[] secret, String label, byte[] context, int length) throws Exception {
        byte[] fullLabel = ("tls13 " + label).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.write((length >>> 8) & 0xff);
        info.write(length & 0xff);
        info.write(fullLabel.length);
        info.write(fullLabel);
        info.write(context.length);
        info.write(context);
        return hkdfExpand(secret, info.toByteArray(), length);
    }

    private byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
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
        return Arrays.copyOf(out.toByteArray(), length);
    }

    private byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private byte[] sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data);
    }

    public static final class HandshakeSecrets {

        private final Tls13KeySchedule schedule;
        private final byte[] handshakeSecret;
        private final byte[] clientHandshakeSecret;
        private final byte[] serverHandshakeSecret;
        private final RealityCryptoContext clientHandshakeContext;
        private final RealityCryptoContext serverHandshakeContext;

        private HandshakeSecrets(
                Tls13KeySchedule schedule,
                byte[] handshakeSecret,
                byte[] clientHandshakeSecret,
                byte[] serverHandshakeSecret,
                RealityCryptoContext clientHandshakeContext,
                RealityCryptoContext serverHandshakeContext) {
            this.schedule = schedule;
            this.handshakeSecret = handshakeSecret;
            this.clientHandshakeSecret = clientHandshakeSecret;
            this.serverHandshakeSecret = serverHandshakeSecret;
            this.clientHandshakeContext = clientHandshakeContext;
            this.serverHandshakeContext = serverHandshakeContext;
        }

        public RealityCryptoContext clientHandshakeContext() {
            return clientHandshakeContext;
        }

        public RealityCryptoContext serverHandshakeContext() {
            return serverHandshakeContext;
        }

        public byte[] clientFinished(byte[] transcript) {
            try {
                byte[] finishedKey = schedule.hkdfExpandLabel(clientHandshakeSecret, "finished", new byte[0], HASH_LENGTH);
                byte[] verifyData = schedule.hmacSha256(finishedKey, schedule.sha256(transcript));
                byte[] message = new byte[4 + verifyData.length];
                message[0] = 20;
                message[1] = (byte) ((verifyData.length >>> 16) & 0xff);
                message[2] = (byte) ((verifyData.length >>> 8) & 0xff);
                message[3] = (byte) (verifyData.length & 0xff);
                System.arraycopy(verifyData, 0, message, 4, verifyData.length);
                return message;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to create TLS 1.3 client Finished", e);
            }
        }

        public byte[] serverFinishedVerifyData(byte[] transcript) {
            try {
                byte[] finishedKey = schedule.hkdfExpandLabel(serverHandshakeSecret, "finished", new byte[0], HASH_LENGTH);
                return schedule.hmacSha256(finishedKey, schedule.sha256(transcript));
            } catch (Exception e) {
                throw new IllegalStateException("Unable to create TLS 1.3 server Finished verification data", e);
            }
        }

        public boolean verifyServerFinished(byte[] transcript, byte[] verifyData) {
            return MessageDigest.isEqual(serverFinishedVerifyData(transcript), verifyData);
        }

        public ApplicationSecrets applicationTraffic(byte[] transcript) {
            return schedule.applicationTraffic(handshakeSecret, transcript);
        }
    }

    public static final class ApplicationSecrets {

        private final RealityCryptoContext clientApplicationContext;
        private final RealityCryptoContext serverApplicationContext;

        private ApplicationSecrets(
                RealityCryptoContext clientApplicationContext,
                RealityCryptoContext serverApplicationContext) {
            this.clientApplicationContext = clientApplicationContext;
            this.serverApplicationContext = serverApplicationContext;
        }

        public RealityCryptoContext clientApplicationContext() {
            return clientApplicationContext;
        }

        public RealityCryptoContext serverApplicationContext() {
            return serverApplicationContext;
        }
    }
}
