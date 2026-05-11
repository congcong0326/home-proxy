package org.congcong.proxyworker.outbound.reality.tls;

import org.congcong.proxyworker.outbound.reality.config.RealityClientConfig;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

public final class RealityHandshakeEngine implements TlsApplicationDataWriter, TlsApplicationDataReader {

    private final RealityClientConfig config;
    private final RealityClientHelloFactory clientHelloFactory;
    private final TlsHandshakeMessageBuffer messageBuffer = new TlsHandshakeMessageBuffer();
    private final Tls13ApplicationData applicationData = new Tls13ApplicationData();
    private final RealityServerVerifier serverVerifier = new RealityServerVerifier();
    private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
    private HandshakeState state = HandshakeState.IDLE;
    private RealityClientHello currentHello;
    private Tls13KeySchedule.HandshakeSecrets handshakeSecrets;
    private RealityCryptoContext serverHandshakeContext;
    private RealityCryptoContext serverApplicationContext;
    private RealityCryptoContext clientApplicationContext;
    private RealityTemporaryCertificate realityCertificate;
    private boolean realityCertificateVerified;
    private boolean certificateVerifyVerified;
    private boolean handshakeComplete;

    public RealityHandshakeEngine(RealityClientConfig config, RealityClientHelloFactory clientHelloFactory) {
        this.config = config;
        this.clientHelloFactory = clientHelloFactory;
    }

    public List<TlsRecord> start() {
        currentHello = clientHelloFactory.createSession(config);
        byte[] payload = currentHello.payload();
        transcript.reset();
        transcript.write(payload, 0, payload.length);
        state = HandshakeState.CLIENT_HELLO_SENT;
        handshakeSecrets = null;
        serverHandshakeContext = null;
        serverApplicationContext = null;
        clientApplicationContext = null;
        realityCertificate = null;
        realityCertificateVerified = false;
        certificateVerifyVerified = false;
        handshakeComplete = false;
        messageBuffer.reset();
        return Collections.singletonList(new TlsRecord(TlsRecordType.HANDSHAKE, 0x0301, payload));
    }

    public List<TlsRecord> accept(TlsRecord record) {
        if (state == HandshakeState.IDLE || state == HandshakeState.FAILED) {
            state = HandshakeState.FAILED;
            throw new IllegalStateException("Unexpected record before handshake started");
        }
        if (record.type() == TlsRecordType.CHANGE_CIPHER_SPEC) {
            return Collections.emptyList();
        }

        if (record.type() == TlsRecordType.APPLICATION_DATA) {
            RealityCryptoContext decryptContext = handshakeComplete ? serverApplicationContext : serverHandshakeContext;
            if (decryptContext == null) {
                return Collections.emptyList();
            }
            Tls13Plaintext plaintext = applicationData.decrypt(record, decryptContext);
            if (plaintext.contentType() == TlsRecordType.HANDSHAKE) {
                return processHandshakePayload(plaintext.payload());
            }
            return Collections.emptyList();
        }

        if (record.type() == TlsRecordType.HANDSHAKE) {
            return processHandshakePayload(record.payload());
        }
        return Collections.emptyList();
    }

    private List<TlsRecord> processHandshakePayload(byte[] payload) {
        List<TlsRecord> outbound = new ArrayList<TlsRecord>();
        for (TlsHandshakeMessage message : messageBuffer.append(payload)) {
            byte[] encodedMessage = encodeHandshakeMessage(message);
            if (message.type() == TlsHandshakeMessageType.SERVER_HELLO) {
                state = HandshakeState.SERVER_HELLO_RECEIVED;
                transcript.write(encodedMessage, 0, encodedMessage.length);
                handshakeSecrets = deriveHandshakeSecrets(TlsServerHello.from(message));
                serverHandshakeContext = handshakeSecrets.serverHandshakeContext();
                continue;
            }
            if (message.type() == TlsHandshakeMessageType.CERTIFICATE) {
                TlsCertificateMessage certificateMessage = TlsCertificateMessage.fromBody(message.body());
                realityCertificate = RealityTemporaryCertificate.fromDer(certificateMessage.leafDer());
                if (!serverVerifier.verifyRealityCertificate(realityCertificate, currentHello.authKey())) {
                    state = HandshakeState.FAILED;
                    throw new IllegalStateException("REALITY temporary certificate verification failed");
                }
                realityCertificateVerified = true;
                transcript.write(encodedMessage, 0, encodedMessage.length);
                continue;
            }
            if (message.type() == TlsHandshakeMessageType.CERTIFICATE_VERIFY) {
                if (!realityCertificateVerified || realityCertificate == null) {
                    state = HandshakeState.FAILED;
                    throw new IllegalStateException("TLS CertificateVerify arrived before REALITY certificate");
                }
                TlsCertificateVerifyMessage certificateVerify = TlsCertificateVerifyMessage.fromBody(message.body());
                if (!serverVerifier.verifyCertificateVerify(
                        realityCertificate.rawEd25519PublicKey(),
                        transcript.toByteArray(),
                        certificateVerify)) {
                    state = HandshakeState.FAILED;
                    throw new IllegalStateException("TLS CertificateVerify verification failed");
                }
                certificateVerifyVerified = true;
                transcript.write(encodedMessage, 0, encodedMessage.length);
                continue;
            }
            if (message.type() == TlsHandshakeMessageType.FINISHED && !handshakeComplete) {
                if (!realityCertificateVerified || !certificateVerifyVerified) {
                    state = HandshakeState.FAILED;
                    throw new IllegalStateException("TLS server Finished arrived before verified REALITY certificate");
                }
                byte[] transcriptBeforeFinished = transcript.toByteArray();
                if (!handshakeSecrets.verifyServerFinished(transcriptBeforeFinished, message.body())) {
                    state = HandshakeState.FAILED;
                    throw new IllegalStateException("TLS server Finished verification failed");
                }
                transcript.write(encodedMessage, 0, encodedMessage.length);
                byte[] transcriptThroughServerFinished = transcript.toByteArray();
                Tls13KeySchedule.ApplicationSecrets applicationSecrets =
                        handshakeSecrets.applicationTraffic(transcriptThroughServerFinished);
                clientApplicationContext = applicationSecrets.clientApplicationContext();
                serverApplicationContext = applicationSecrets.serverApplicationContext();
                byte[] clientFinished = handshakeSecrets.clientFinished(transcriptThroughServerFinished);
                outbound.add(applicationData.encrypt(
                        clientFinished,
                        TlsRecordType.HANDSHAKE,
                        handshakeSecrets.clientHandshakeContext()));
                transcript.write(clientFinished, 0, clientFinished.length);
                handshakeComplete = true;
                state = HandshakeState.HANDSHAKE_COMPLETE;
                continue;
            }
            transcript.write(encodedMessage, 0, encodedMessage.length);
        }
        return outbound;
    }

    private byte[] encodeHandshakeMessage(TlsHandshakeMessage message) {
        byte[] body = message.body();
        byte[] encoded = new byte[4 + body.length];
        encoded[0] = (byte) message.type().code();
        encoded[1] = (byte) ((body.length >>> 16) & 0xff);
        encoded[2] = (byte) ((body.length >>> 8) & 0xff);
        encoded[3] = (byte) (body.length & 0xff);
        System.arraycopy(body, 0, encoded, 4, body.length);
        return encoded;
    }

    boolean verifyServerFinishedForTest(byte[] transcriptBeforeFinished, TlsHandshakeMessage finished) {
        return handshakeSecrets.verifyServerFinished(transcriptBeforeFinished, finished.body());
    }

    private Tls13KeySchedule.HandshakeSecrets deriveHandshakeSecrets(TlsServerHello serverHello) {
        try {
            byte[] sharedSecret = new byte[32];
            currentHello.privateKey().generateSecret(
                    new X25519PublicKeyParameters(serverHello.keyShare(), 0),
                    sharedSecret,
                    0);
            return new Tls13KeySchedule().handshakeTraffic(sharedSecret, transcript.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive handshake traffic keys", e);
        }
    }

    public TlsRecord encryptApplicationData(byte[] payload) {
        if (!handshakeComplete || clientApplicationContext == null) {
            throw new IllegalStateException("TLS handshake is not complete");
        }
        return applicationData.encrypt(payload, TlsRecordType.APPLICATION_DATA, clientApplicationContext);
    }

    public byte[] decryptApplicationData(TlsRecord record) {
        Tls13Plaintext plaintext = decryptApplicationPlaintext(record);
        if (plaintext.contentType() != TlsRecordType.APPLICATION_DATA) {
            throw new IllegalStateException("TLS inner content type is not application data");
        }
        return plaintext.payload();
    }

    public Tls13Plaintext decryptApplicationPlaintext(TlsRecord record) {
        if (!handshakeComplete || serverApplicationContext == null) {
            throw new IllegalStateException("TLS handshake is not complete");
        }
        if (record.type() != TlsRecordType.APPLICATION_DATA) {
            throw new IllegalArgumentException("Expected TLS application data record, got " + record.type());
        }
        return applicationData.decrypt(record, serverApplicationContext);
    }

    public HandshakeState state() {
        return state;
    }

    public boolean handshakeComplete() {
        return handshakeComplete;
    }
}
