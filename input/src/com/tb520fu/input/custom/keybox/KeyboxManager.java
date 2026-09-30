/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.input.custom.keybox;

import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import com.android.internal.org.bouncycastle.asn1.ASN1Primitive;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.DERNull;
import com.android.internal.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import com.android.internal.org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import com.android.internal.org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import com.android.internal.org.bouncycastle.asn1.sec.ECPrivateKey;
import com.android.internal.org.bouncycastle.asn1.x509.AlgorithmIdentifier;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keybox storage of the tb520fu key attestation service, the ROM port of the
 * TrickyStore / TEESimulator keybox handling.
 *
 * Everything lives in system_server's data so the private keys are never
 * readable by apps:
 *
 *   /data/system/tb520fu/keybox.xml           the active keybox
 *   /data/system/tb520fu/keybox.json          source / version / serial of it
 *   /data/system/tb520fu/keyboxes/*.xml       spare keyboxes, ready to swap in
 *   /data/system/tb520fu/revoked.txt          serials Google revoked
 *   /data/system/tb520fu/targets.txt          "all" or "list" + one package per line
 *   /data/system/tb520fu/auto_target          "1" while new apps are added to the list
 *
 * Like Specter, the app keeps several keyboxes around and swaps the active one
 * when it is revoked or fails, so a renewal never leaves the device without a
 * working keybox.
 *
 * A keybox has an EC and an RSA key set, each with a private key and a
 * certificate chain whose first certificate is signed by the private key (the
 * same layout TrickyStore / TEESimulator and Specter's catalog use).
 */
final class KeyboxManager {
    private static final String TAG = "TB520FUKeybox";

    private static final File DIR = new File("/data/system/tb520fu");
    private static final File KEYBOX_FILE = new File(DIR, "keybox.xml");
    private static final File META_FILE = new File(DIR, "keybox.json");
    private static final File POOL_DIR = new File(DIR, "keyboxes");
    private static final File REVOKED_FILE = new File(DIR, "revoked.txt");
    private static final File TARGETS_FILE = new File(DIR, "targets.txt");
    private static final File AUTO_TARGET_FILE = new File(DIR, "auto_target");

    /** How many spare keyboxes are kept; the rest rotate out, oldest first. */
    private static final int POOL_MAX = 2;

    /** Default target list, mirrors TEESimulator-RS's target.txt defaults. */
    private static final String[] DEFAULT_TARGETS = {
            "com.google.android.gms",
            "com.android.vending",
    };

    private static final Object LOCK = new Object();

    private static Store sStore;
    private static long sGeneration = 0;
    private static String sSource = "";
    private static String sVersion = "";
    private static boolean sTargetsLoaded = false;
    private static boolean sAllTargets = false;
    private static final Set<String> sTargets = new LinkedHashSet<>();
    private static long sTargetsVersion = 0;
    private static boolean sAutoTargetLoaded = false;
    private static boolean sAutoTarget = false;
    /** Parsed pool, built once per change; pool() never parses on a hot path. */
    private static List<PoolEntry> sPoolCache;

    private KeyboxManager() {}

    static final class Keybox {
        final PrivateKey key;
        final List<X509Certificate> certs;

        Keybox(PrivateKey key, List<X509Certificate> certs) {
            this.key = key;
            this.certs = certs;
        }
    }

    static final class Store {
        final Keybox ec;
        final Keybox rsa;
        final String serial;

        Store(Keybox ec, Keybox rsa, String serial) {
            this.ec = ec;
            this.rsa = rsa;
            this.serial = serial;
        }
    }

    static final class PoolEntry {
        final File file;
        final String source;
        final String version;
        final String serial;
        final boolean active;
        final boolean revoked;

        PoolEntry(File file, String source, String version, String serial, boolean active,
                boolean revoked) {
            this.file = file;
            this.source = source;
            this.version = version;
            this.serial = serial;
            this.active = active;
            this.revoked = revoked;
        }
    }

    /** Loads the active store from disk once; null when none is installed. */
    static Store store() {
        synchronized (LOCK) {
            if (sStore == null && KEYBOX_FILE.isFile()) {
                try {
                    sStore = parse(readFile(KEYBOX_FILE));
                    loadMeta();
                } catch (Throwable t) {
                    Log.w(TAG, "installed keybox is unreadable", t);
                }
            }
            return sStore;
        }
    }

    static long generation() {
        synchronized (LOCK) {
            return sGeneration;
        }
    }

    static String source() {
        synchronized (LOCK) {
            return sSource;
        }
    }

    static String version() {
        synchronized (LOCK) {
            return sVersion;
        }
    }

    static boolean installed() {
        return store() != null;
    }

    static String activeSerial() {
        Store store = store();
        return store == null ? null : store.serial;
    }

    /** Validates and activates a keybox, storing it in the pool too. */
    static String install(String xml, String source, String version) {
        try {
            if (store(xml, source, version) == null) return null;
        } catch (Throwable t) {
            Log.w(TAG, "keybox rejected", t);
            return null;
        }
        synchronized (LOCK) {
            return activate(source, version, false);
        }
    }

    /**
     * Validates a keybox and adds it to the pool without activating it; the
     * serial is returned, or null when the file is not a valid keybox.
     */
    static String store(String xml, String source, String version) {
        Store parsed;
        try {
            parsed = parse(xml);
        } catch (Throwable t) {
            Log.w(TAG, "keybox rejected", t);
            return null;
        }
        synchronized (LOCK) {
            try {
                POOL_DIR.mkdirs();
                for (PoolEntry entry : pool()) {
                    if (parsed.serial.equals(entry.serial)) return entry.serial;
                }
                File file = new File(POOL_DIR, sanitize(source) + "-" + sanitize(version) + ".xml");
                writeFile(file, xml.getBytes(StandardCharsets.UTF_8));
                sPoolCache = null;
                prunePool();
            } catch (Throwable t) {
                Log.w(TAG, "keybox pool write failed", t);
                return null;
            }
            return parsed.serial;
        }
    }

    private static void prunePool() {
        // Keep the active keybox plus at most POOL_MAX - 1 spares; the oldest
        // spares rotate out first.
        String active = activeSerial();
        List<PoolEntry> spares = new ArrayList<>();
        for (PoolEntry entry : pool()) {
            if (!entry.serial.equals(active)) spares.add(entry);
        }
        int keep = POOL_MAX - 1;
        if (spares.size() <= keep) return;
        Collections.sort(spares, Comparator.comparingLong(e -> e.file.lastModified()));
        for (int i = 0; i < spares.size() - keep; i++) {
            //noinspection ResultOfMethodCallIgnored
            spares.get(i).file.delete();
        }
    }

    /** Swaps in the next pooled keybox that is not revoked and not active. */
    static PoolEntry rotate() {
        synchronized (LOCK) {
            String active = activeSerial();
            for (PoolEntry entry : pool()) {
                if (!entry.revoked && !entry.serial.equals(active)) {
                    String serial = activate(entry.source, entry.version, true);
                    if (serial != null) return bySerial(serial);
                }
            }
            return null;
        }
    }

    /**
     * Copies a pooled keybox (or, without one, the current file's metadata) to
     * the active slot.
     */
    private static String activate(String source, String version, boolean fromPool) {
        synchronized (LOCK) {
            File sourceFile = null;
            for (PoolEntry entry : pool()) {
                if (entry.source.equals(source) && entry.version.equals(version)) {
                    sourceFile = entry.file;
                    break;
                }
            }
            if (sourceFile == null && !fromPool) {
                // install() stored it under this name just before.
                sourceFile = new File(POOL_DIR, sanitize(source) + "-" + sanitize(version) + ".xml");
            }
            if (sourceFile == null || !sourceFile.isFile()) return null;
            try {
                Store parsed = parse(readFile(sourceFile));
                DIR.mkdirs();
                writeFile(KEYBOX_FILE, readFile(sourceFile).getBytes(StandardCharsets.UTF_8));
                JSONObject meta = new JSONObject();
                meta.put("source", source == null ? "" : source);
                meta.put("version", version == null ? "" : version);
                meta.put("serial", parsed.serial);
                writeFile(META_FILE, meta.toString().getBytes(StandardCharsets.UTF_8));
                sStore = parsed;
                sSource = source == null ? "" : source;
                sVersion = version == null ? "" : version;
                sGeneration++;
                sPoolCache = null;
                return parsed.serial;
            } catch (Throwable t) {
                Log.e(TAG, "keybox activate failed", t);
                return null;
            }
        }
    }

    static void clear() {
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            KEYBOX_FILE.delete();
            //noinspection ResultOfMethodCallIgnored
            META_FILE.delete();
            sStore = null;
            sSource = "";
            sVersion = "";
            sGeneration++;
            sPoolCache = null;
        }
    }

    /** Removes the whole pool as well, for the "delete keybox" action. */
    static void clearAll() {
        synchronized (LOCK) {
            clear();
            File[] files = POOL_DIR.listFiles();
            if (files != null) {
                for (File file : files) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            REVOKED_FILE.delete();
            sPoolCache = null;
        }
    }

    /**
     * Target list cleanup for the boot after the TEE simulator switch was
     * turned off: no packages, no automatic target state (and no trace).
     */
    static void wipeTargets() {
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            TARGETS_FILE.delete();
            //noinspection ResultOfMethodCallIgnored
            AUTO_TARGET_FILE.delete();
            sTargets.clear();
            sAllTargets = false;
            sTargetsLoaded = false;
            sTargetsVersion++;
            sAutoTarget = false;
            sAutoTargetLoaded = true;
        }
    }

    static List<PoolEntry> pool() {
        synchronized (LOCK) {
            if (sPoolCache != null) return new ArrayList<>(sPoolCache);
            List<PoolEntry> entries = new ArrayList<>();
            File[] files = POOL_DIR.listFiles();
            Store active = store();
            String activeSerial = active == null ? null : active.serial;
            if (files != null) {
                for (File file : files) {
                    if (!file.getName().endsWith(".xml")) continue;
                    try {
                        Store parsed = parse(readFile(file));
                        String name = file.getName().substring(0, file.getName().length() - 4);
                        int dash = name.lastIndexOf('-');
                        String source = dash > 0 ? name.substring(0, dash) : name;
                        String version = dash > 0 ? name.substring(dash + 1) : "";
                        entries.add(new PoolEntry(file, source, version, parsed.serial,
                                parsed.serial.equals(activeSerial), isRevoked(parsed.serial)));
                    } catch (Throwable t) {
                        Log.w(TAG, "bad pool keybox " + file.getName(), t);
                    }
                }
            }
            sPoolCache = entries;
            return new ArrayList<>(entries);
        }
    }

    private static PoolEntry bySerial(String serial) {
        for (PoolEntry entry : pool()) {
            if (entry.serial.equals(serial)) return entry;
        }
        return null;
    }

    static boolean isRevoked(String serial) {
        synchronized (LOCK) {
            if (serial == null || !REVOKED_FILE.isFile()) return false;
            try {
                for (String line : readFile(REVOKED_FILE).split("\n")) {
                    if (serial.equalsIgnoreCase(line.trim())) return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    static void markRevoked(String serial) {
        if (serial == null || serial.isEmpty()) return;
        synchronized (LOCK) {
            if (isRevoked(serial)) return;
            try {
                DIR.mkdirs();
                try (FileOutputStream out = new FileOutputStream(REVOKED_FILE, true)) {
                    out.write((serial + "\n").getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                REVOKED_FILE.setReadable(false, false);
                //noinspection ResultOfMethodCallIgnored
                REVOKED_FILE.setReadable(true, true);
                sPoolCache = null;
            } catch (Throwable t) {
                Log.w(TAG, "revoked write failed", t);
            }
        }
    }

    static int revokedCount() {
        synchronized (LOCK) {
            if (!REVOKED_FILE.isFile()) return 0;
            try {
                int count = 0;
                for (String line : readFile(REVOKED_FILE).split("\n")) {
                    if (!line.trim().isEmpty()) count++;
                }
                return count;
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    static boolean allTargets() {
        loadTargets();
        synchronized (LOCK) {
            return sAllTargets;
        }
    }

    static String[] targets() {
        loadTargets();
        synchronized (LOCK) {
            return sTargets.toArray(new String[0]);
        }
    }

    static boolean isTargeted(String pkg) {
        loadTargets();
        synchronized (LOCK) {
            return sAllTargets || sTargets.contains(pkg);
        }
    }

    /** Bumped whenever the target list changes; consumers cache against it. */
    static long targetsVersion() {
        synchronized (LOCK) {
            return sTargetsVersion;
        }
    }

    static void setTargets(String[] packages, boolean all) {
        synchronized (LOCK) {
            sAllTargets = all;
            sTargets.clear();
            if (packages != null) {
                for (String p : packages) {
                    if (p != null && !p.isEmpty()) sTargets.add(p);
                }
            }
            sTargetsVersion++;
            saveTargets();
        }
    }

    static void addTarget(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        synchronized (LOCK) {
            loadTargets();
            if (sTargets.add(pkg)) {
                sTargetsVersion++;
                saveTargets();
            }
        }
    }

    static void removeTarget(String pkg) {
        if (pkg == null) return;
        synchronized (LOCK) {
            loadTargets();
            if (sTargets.remove(pkg)) {
                sTargetsVersion++;
                saveTargets();
            }
        }
    }

    private static void saveTargets() {
        StringBuilder out = new StringBuilder(sAllTargets ? "all" : "list").append('\n');
        for (String p : sTargets) out.append(p).append('\n');
        try {
            DIR.mkdirs();
            writeFile(TARGETS_FILE, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "targets write failed", t);
        }
    }

    private static void loadTargets() {
        synchronized (LOCK) {
            if (sTargetsLoaded) return;
            sTargetsLoaded = true;
            try {
                if (TARGETS_FILE.isFile()) {
                    String[] lines = readFile(TARGETS_FILE).split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.isEmpty()) continue;
                        if (i == 0 && "all".equals(line)) {
                            sAllTargets = true;
                            continue;
                        }
                        if (i == 0 && "list".equals(line)) continue;
                        sTargets.add(line);
                    }
                } else {
                    sTargets.addAll(Arrays.asList(DEFAULT_TARGETS));
                }
            } catch (Throwable t) {
                Log.w(TAG, "targets load failed", t);
                sTargets.addAll(Arrays.asList(DEFAULT_TARGETS));
            }
        }
    }

    static boolean autoTarget() {
        synchronized (LOCK) {
            if (!sAutoTargetLoaded) {
                sAutoTargetLoaded = true;
                if (AUTO_TARGET_FILE.isFile()) {
                    try {
                        sAutoTarget = "1".equals(readFile(AUTO_TARGET_FILE).trim());
                    } catch (Throwable t) {
                        sAutoTarget = false;
                    }
                }
            }
            return sAutoTarget;
        }
    }

    static void setAutoTarget(boolean enabled) {
        synchronized (LOCK) {
            sAutoTarget = enabled;
            sAutoTargetLoaded = true;
            try {
                DIR.mkdirs();
                writeFile(AUTO_TARGET_FILE,
                        (enabled ? "1" : "0").getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                Log.w(TAG, "auto target write failed", t);
            }
        }
    }

    private static String sanitize(String name) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isEmpty() ? "unknown" : clean;
    }

    private static void loadMeta() {
        try {
            if (!META_FILE.isFile()) return;
            JSONObject meta = new JSONObject(readFile(META_FILE));
            sSource = meta.optString("source", "");
            sVersion = meta.optString("version", "");
        } catch (Throwable t) {
            Log.w(TAG, "meta load failed", t);
        }
    }

    /**
     * Parses a keybox XML. Both an EC and an RSA key with at least one
     * certificate are required, which is what the keybox files in the wild
     * (and TrickyStore's checks) provide.
     */
    static Store parse(String xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));

        int keyboxes = -1;
        String alg = null;
        String priv = null;
        List<String> certs = null;
        StringBuilder text = new StringBuilder();
        boolean inPrivate = false;
        boolean inCertificate = false;

        Keybox ec = null;
        Keybox rsa = null;

        for (int ev = p.next(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                switch (p.getName()) {
                    case "NumberOfKeyboxes":
                        text.setLength(0);
                        break;
                    case "Key":
                        alg = p.getAttributeValue(null, "algorithm");
                        if ("ecdsa".equalsIgnoreCase(alg)) alg = "EC";
                        else if ("rsa".equalsIgnoreCase(alg)) alg = "RSA";
                        else alg = null;
                        priv = null;
                        certs = new ArrayList<>();
                        break;
                    case "PrivateKey":
                        if (!"pem".equalsIgnoreCase(p.getAttributeValue(null, "format"))) {
                            throw new IllegalArgumentException("PrivateKey format is not pem");
                        }
                        inPrivate = true;
                        text.setLength(0);
                        break;
                    case "Certificate":
                        if (!"pem".equalsIgnoreCase(p.getAttributeValue(null, "format"))) {
                            throw new IllegalArgumentException("Certificate format is not pem");
                        }
                        inCertificate = true;
                        text.setLength(0);
                        break;
                    default:
                        break;
                }
            } else if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                text.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                switch (p.getName()) {
                    case "NumberOfKeyboxes":
                        try {
                            keyboxes = Integer.parseInt(text.toString().trim());
                        } catch (NumberFormatException e) {
                            keyboxes = -1;
                        }
                        break;
                    case "PrivateKey":
                        if (inPrivate && alg != null) priv = text.toString();
                        inPrivate = false;
                        break;
                    case "Certificate":
                        if (inCertificate && alg != null) certs.add(text.toString());
                        inCertificate = false;
                        break;
                    case "Key":
                        if (alg != null && priv != null && certs != null && !certs.isEmpty()) {
                            PrivateKey key = decodePrivateKey(priv, alg);
                            List<X509Certificate> chain = decodeCertificates(certs);
                            Keybox keybox = new Keybox(key, chain);
                            if ("EC".equals(alg)) ec = keybox;
                            else rsa = keybox;
                        }
                        alg = null;
                        priv = null;
                        certs = null;
                        break;
                    default:
                        break;
                }
                text.setLength(0);
            }
        }

        if (keyboxes != 1) {
            throw new IllegalArgumentException("expected NumberOfKeyboxes 1, got " + keyboxes);
        }
        if (ec == null || rsa == null) {
            throw new IllegalArgumentException("keybox needs an ecdsa and an rsa key");
        }
        if (ec.certs.isEmpty() || rsa.certs.isEmpty()) {
            throw new IllegalArgumentException("keybox certificate chain is empty");
        }
        return new Store(ec, rsa, ec.certs.get(0).getSerialNumber().toString(16));
    }

    private static PrivateKey decodePrivateKey(String encoded, String algorithm) throws Exception {
        byte[] bytes = decodePem(encoded);
        ASN1Primitive primitive = ASN1Primitive.fromByteArray(bytes);
        if ("EC".equals(algorithm)) {
            try {
                PrivateKeyInfo info = PrivateKeyInfo.getInstance(primitive);
                return KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));
            } catch (Exception e) {
                ASN1Sequence seq = ASN1Sequence.getInstance(primitive);
                ECPrivateKey ecKey = ECPrivateKey.getInstance(seq);
                AlgorithmIdentifier algId = new AlgorithmIdentifier(
                        com.android.internal.org.bouncycastle.asn1.x9.X9ObjectIdentifiers.id_ecPublicKey,
                        ecKey.getParameters());
                PrivateKeyInfo info = new PrivateKeyInfo(algId, ecKey);
                return KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));
            }
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception e) {
            RSAPrivateKey rsaKey = RSAPrivateKey.getInstance(primitive);
            AlgorithmIdentifier algId = new AlgorithmIdentifier(
                    PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);
            PrivateKeyInfo info = new PrivateKeyInfo(algId, rsaKey);
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));
        }
    }

    private static List<X509Certificate> decodeCertificates(List<String> encoded) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        for (String cert : encoded) {
            byte[] bytes = decodePem(cert);
            chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bytes)));
        }
        return chain;
    }

    private static byte[] decodePem(String input) {
        String base64 = input
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        return Base64.decode(base64, Base64.DEFAULT);
    }

    static String readFile(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(true, true);
    }
}
