package org.congcong.proxyworker.outbound.reality.tls;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.Certificate;

public final class RealityTemporaryCertificate {
    private final byte[] rawEd25519PublicKey;
    private final byte[] signature;

    private RealityTemporaryCertificate(byte[] rawEd25519PublicKey, byte[] signature) {
        this.rawEd25519PublicKey = copy(rawEd25519PublicKey);
        this.signature = copy(signature);
    }

    public static RealityTemporaryCertificate fromDer(byte[] der) {
        try {
            ASN1InputStream input = new ASN1InputStream(der);
            try {
                ASN1Primitive primitive = input.readObject();
                Certificate certificate = Certificate.getInstance(primitive);
                return new RealityTemporaryCertificate(
                        certificate.getSubjectPublicKeyInfo().getPublicKeyData().getBytes(),
                        certificate.getSignature().getBytes());
            } finally {
                input.close();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse REALITY temporary certificate", e);
        }
    }

    public byte[] rawEd25519PublicKey() {
        return copy(rawEd25519PublicKey);
    }

    public byte[] signature() {
        return copy(signature);
    }

    private static byte[] copy(byte[] value) {
        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        return copy;
    }
}
