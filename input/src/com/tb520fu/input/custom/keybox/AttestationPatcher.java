/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.input.custom.keybox;

import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Primitive;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.jce.provider.BouncyCastleProvider;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites a hardware attestation certificate chain so it is signed by the
 * keybox instead of the device keys (the PATCH mode of TrickyStore /
 * TEESimulator-RS).
 *
 * The leaf public key is kept, so the app's real TEE key still signs and
 * verifies; only the certificate is re-issued: the issuer becomes the keybox
 * certificate, the attestation extension keeps the challenge and all other
 * fields but claims a locked, verified boot state and the configured patch
 * levels. The chain becomes [leaf, keybox certificate, ... root].
 */
final class AttestationPatcher {
    private static final String TAG = "TB520FUKeybox";

    private static final ASN1ObjectIdentifier ATTESTATION_OID =
            new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
    private static final ASN1ObjectIdentifier EC_OID =
            new ASN1ObjectIdentifier("1.2.840.10045.2.1");
    private static final ASN1ObjectIdentifier RSA_OID =
            new ASN1ObjectIdentifier("1.2.840.113549.1.1.1");

    private static final int TAG_ROOT_OF_TRUST = 704;
    private static final int TAG_OS_PATCH_LEVEL = 706;
    private static final int TAG_VENDOR_PATCH_LEVEL = 718;
    private static final int TAG_BOOT_PATCH_LEVEL = 719;

    private static boolean sBcReady = false;

    private AttestationPatcher() {}

    static final class Result {
        final byte[] leaf;
        final List<byte[]> chain;

        Result(byte[] leaf, List<byte[]> chain) {
            this.leaf = leaf;
            this.chain = chain;
        }
    }

    /**
     * Patches the leaf of a real attestation chain; null when the chain has no
     * attestation extension (nothing to spoof) or when the keybox cannot sign
     * it.
     *
     * @param patchLevel YYYYMM patch level put into the attestation extension
     */
    static Result patch(KeyboxManager.Store store, byte[] leafDer, List<byte[]> chainDers,
            int patchLevel) {
        try {
            X509CertificateHolder holder = new X509CertificateHolder(leafDer);
            Extension attestation = holder.getExtensions().getExtension(ATTESTATION_OID);
            if (attestation == null) return null;

            ASN1Sequence keyDescription = ASN1Sequence.getInstance(attestation.getParsedValue());
            if (keyDescription == null || keyDescription.size() < 8) return null;

            ASN1Encodable[] fields = new ASN1Encodable[keyDescription.size()];
            for (int i = 0; i < fields.length; i++) fields[i] = keyDescription.getObjectAt(i);

            int teeIndex = fields.length - 1;
            ASN1Sequence teeEnforced = ASN1Sequence.getInstance(fields[teeIndex]);
            if (teeEnforced == null) return null;

            List<ASN1Encodable> patched = new ArrayList<>();
            for (int i = 0; i < teeEnforced.size(); i++) {
                ASN1TaggedObject tagged = ASN1TaggedObject.getInstance(teeEnforced.getObjectAt(i));
                int tag = tagged.getTagNo();
                if (tag == TAG_ROOT_OF_TRUST) {
                    patched.add(new DERTaggedObject(true, tag, patchRootOfTrust(tagged)));
                } else if (tag == TAG_OS_PATCH_LEVEL || tag == TAG_VENDOR_PATCH_LEVEL
                        || tag == TAG_BOOT_PATCH_LEVEL) {
                    patched.add(new DERTaggedObject(true, tag, new ASN1Integer(patchLevel)));
                } else {
                    patched.add(tagged);
                }
            }
            fields[teeIndex] = new DERSequence(patched.toArray(new ASN1Encodable[0]));
            ASN1Sequence patchedKeyDescription = new DERSequence(fields);

            // Sign with the keybox key that matches the leaf's public key.
            KeyboxManager.Keybox signer = selectSigner(store, holder.getSubjectPublicKeyInfo());
            if (signer == null || signer.certs.isEmpty()) return null;

            boolean ec = isEc(holder.getSubjectPublicKeyInfo());
            X509Certificate signerCert = signer.certs.get(0);
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    new X500Name(signerCert.getSubjectX500Principal().getName()),
                    holder.getSerialNumber(),
                    holder.getNotBefore(),
                    holder.getNotAfter(),
                    holder.getSubject(),
                    holder.getSubjectPublicKeyInfo());
            // Every extension but the attestation extension and the authority
            // key identifier (the signer changed) is kept as it was.
            for (ASN1ObjectIdentifier oid : holder.getExtensions().getExtensionOIDs()) {
                if (ATTESTATION_OID.equals(oid) || Extension.authorityKeyIdentifier.equals(oid)) {
                    continue;
                }
                Extension ext = holder.getExtensions().getExtension(oid);
                ASN1Primitive value = ASN1Primitive.fromByteArray(ext.getExtnValue().getOctets());
                builder.addExtension(oid, ext.isCritical(), value);
            }
            builder.addExtension(ATTESTATION_OID, false, patchedKeyDescription);

            ContentSigner contentSigner = signer(ec, signer);
            X509CertificateHolder patchedHolder = builder.build(contentSigner);

            List<byte[]> chain = new ArrayList<>();
            for (X509Certificate cert : signer.certs) chain.add(cert.getEncoded());
            return new Result(patchedHolder.getEncoded(), chain);
        } catch (Throwable t) {
            Log.w(TAG, "attestation patch failed", t);
            return null;
        }
    }

    private static ASN1Sequence patchRootOfTrust(ASN1TaggedObject tagged) throws Exception {
        ASN1Sequence root = ASN1Sequence.getInstance(tagged.getBaseObject());
        ASN1Encodable bootHash = null;
        ASN1Encodable deviceId = null;
        if (root != null) {
            if (root.size() > 0) bootHash = root.getObjectAt(0);
            if (root.size() > 3) deviceId = root.getObjectAt(3);
        }
        if (bootHash == null) bootHash = randomOctets(32);
        if (deviceId == null) deviceId = randomOctets(32);
        // { verified boot hash, device locked, verified boot state, device id }
        return new DERSequence(new ASN1Encodable[] {
                bootHash, ASN1Boolean.TRUE, new ASN1Enumerated(0), deviceId });
    }

    private static ASN1OctetString randomOctets(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return new DEROctetString(bytes);
    }

    private static boolean isEc(SubjectPublicKeyInfo spki) {
        return spki != null && EC_OID.equals(spki.getAlgorithm().getAlgorithm());
    }

    private static KeyboxManager.Keybox selectSigner(KeyboxManager.Store store,
            SubjectPublicKeyInfo spki) {
        if (spki == null) return null;
        ASN1ObjectIdentifier algorithm = spki.getAlgorithm().getAlgorithm();
        if (RSA_OID.equals(algorithm)) return store.rsa;
        if (EC_OID.equals(algorithm)) return store.ec;
        return null;
    }

    private static ContentSigner signer(boolean ec, KeyboxManager.Keybox keybox) throws Exception {
        if (!sBcReady) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
            Security.addProvider(new BouncyCastleProvider());
            sBcReady = true;
        }
        String algorithm = ec ? "SHA256withECDSA" : "SHA256withRSA";
        return new JcaContentSignerBuilder(algorithm)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keybox.key);
    }
}
