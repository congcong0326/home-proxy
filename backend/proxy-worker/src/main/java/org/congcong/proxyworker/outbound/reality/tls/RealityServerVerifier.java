package org.congcong.proxyworker.outbound.reality.tls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

public final class RealityServerVerifier {
    private static final int ED25519_SIGNATURE_SCHEME = 0x0807;
    private static final String SERVER_CERTIFICATE_VERIFY_CONTEXT = "TLS 1.3, server CertificateVerify";

    public boolean verifyRealityCertificate(RealityTemporaryCertificate certificate, byte[] authKey) {
        return verifyRealityCertificatePublicKey(
                certificate.rawEd25519PublicKey(),
                authKey,
                certificate.signature());
    }

    public boolean verifyRealityCertificatePublicKey(byte[] rawEd25519PublicKey, byte[] authKey, byte[] signature) {
        return MessageDigest.isEqual(hmacSha512(authKey, rawEd25519PublicKey), signature);
    }

    public boolean verifyCertificateVerify(
            byte[] rawEd25519PublicKey,
            byte[] transcriptThroughCertificate,
            TlsCertificateVerifyMessage message) {
        if (message.signatureScheme() != ED25519_SIGNATURE_SCHEME) {
            return false;
        }
        byte[] signedContent = certificateVerifySignedContent(transcriptThroughCertificate);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(rawEd25519PublicKey, 0));
        verifier.update(signedContent, 0, signedContent.length);
        return verifier.verifySignature(message.signature());
    }

    public static byte[] certificateVerifySignedContent(byte[] transcriptThroughCertificate) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < 64; i++) {
                out.write(0x20);
            }
            out.write(SERVER_CERTIFICATE_VERIFY_CONTEXT.getBytes(StandardCharsets.US_ASCII));
            out.write(0x00);
            out.write(MessageDigest.getInstance("SHA-256").digest(transcriptThroughCertificate));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build TLS CertificateVerify signed content", e);
        }
    }

    private byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to verify REALITY temporary certificate", e);
        }
    }
}
