/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.os.SystemProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Keybox renewal, the data paths of the Specter module ported to the firmware
 * (Specter itself is a Magisk / KernelSU module; only its catalog, revocation
 * and download logic is used here):
 *
 *   catalog      https://rawbin.dpejoh.com/catalog   (source/version entries)
 *   keybox       https://rawbin.dpejoh.com/key/<source>/<version>
 *   revocation   https://android.googleapis.com/attestation/status?encrypted=0
 *
 * Renewal follows Specter's defaults: one working catalog entry is downloaded
 * and installed as the active keybox, with Specter's fallback list (Yuri/8)
 * when the catalog cannot be reached, and one spare is kept so a revoked
 * keybox can be swapped in without waiting for the network (the pool never
 * holds more than the active one and its pair, and it is only touched here).
 *
 * [renew] swaps the active keybox automatically when it was revoked (the
 * "replace automatically" switch, on by default); with the switch off only an
 * explicit renewal (the button) replaces it. Specter delivers the keyboxes as
 * base64 blobs with a shuffled alphabet; they are decoded here exactly like
 * `keybox.sh` does.
 */
object KeyboxRenewal {

    private const val CATALOG_URL = "https://rawbin.dpejoh.com/catalog"
    private const val KEYBOX_BASE = "https://rawbin.dpejoh.com/key"
    private const val REVOCATION_URL =
        "https://android.googleapis.com/attestation/status?encrypted=0"
    private const val TIMEOUT_MS = 15000

    /** Spares kept besides the active keybox (Specter's single fallback pair). */
    private const val POOL_TARGET = 1

    /** Specter's FALLBACK_KEYBOXES (constants.sh): used when the catalog is down. */
    private val FALLBACK_KEYBOXES = listOf("Yuri" to "8")

    /** keybox.sh decodes the catalog blob with these alphabets (decode_substitution). */
    private const val STD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val SHUFFLED_ALPHABET =
        "1dgWnocayqxU3r6vA5lCIPYfHmkV08b4tz+KMsp2NQ9LRXihODwSj7BEFJ/ZuGTe"

    sealed class Result {
        data class Ok(
            val source: String?,
            val version: String?,
            val serial: String?,
            val spare: Int,
            val activeRevoked: Boolean,
        ) : Result()

        data class Failed(val reason: String) : Result()
    }

    /**
     * @param force true for the "renew now" button: always replaces the active
     *              keybox; false (the periodic job) respects [autoRotate]
     * @param autoRotate swap in a fresh/spare keybox when the active one was
     *                   revoked (the "replace automatically" switch)
     */
    fun renew(force: Boolean, autoRotate: Boolean): Result {
        val revocations = httpGet(REVOCATION_URL)

        // Record what Google revoked among the keyboxes we already hold.
        var activeRevoked = false
        for (entry in IntegrityServiceClient.pool()) {
            val serial = entry.serial
            if (!serial.isNullOrEmpty() && isRevoked(revocations, serial)) {
                IntegrityServiceClient.markRevoked(serial)
                if (entry.active) activeRevoked = true
            }
        }

        val before = IntegrityServiceClient.status() ?: return Result.Failed("service unavailable")
        val needsActive = !before.installed || activeRevoked
        if (force || (autoRotate && needsActive)) {
            val installed = downloadAndInstall(revocations)
                ?: if (autoRotate && needsActive) IntegrityServiceClient.rotate() else null
            if (installed == null && !before.installed) {
                return Result.Failed("no working keybox found")
            }
        }

        // Keep one spare ready, like Specter's fallback pair.
        val spareCount = IntegrityServiceClient.pool().count { !it.active }
        if (spareCount < POOL_TARGET) {
            downloadAndStore(revocations)
        }

        val after = IntegrityServiceClient.status() ?: return Result.Failed("service unavailable")
        if (!after.installed) return Result.Failed("no working keybox found")
        val pool = IntegrityServiceClient.pool()
        return Result.Ok(
            source = after.source,
            version = after.version,
            serial = after.serial,
            spare = pool.count { !it.active },
            activeRevoked = pool.any { it.active && it.revoked },
        )
    }

    fun fetchPif(): String? {
        val template = try {
            android.content.res.Resources.getSystem()
                .getString(com.android.internal.R.string.config_pifUpdateUrl)
        } catch (t: Throwable) {
            return null
        }
        val version = SystemProperties.get("net.pixelos.version", "")
        if (template.isEmpty() || version.isEmpty()) return null
        val url = template.replace("{version}", version)
        return httpGet(url)
    }

    private fun candidates(): List<Pair<String, String>> {
        val catalog = httpGet(CATALOG_URL) ?: return FALLBACK_KEYBOXES
        val fromCatalog = catalogCandidates(catalog)
        return if (fromCatalog.isEmpty()) FALLBACK_KEYBOXES else fromCatalog
    }

    private fun downloadAndInstall(revocations: String?): String? {
        for ((source, version) in candidates().distinct()) {
            val xml = downloadCandidate(source, version, revocations) ?: continue
            val serial = IntegrityServiceClient.install(xml, source, version) ?: continue
            return serial
        }
        return null
    }

    private fun downloadAndStore(revocations: String?): String? {
        val active = IntegrityServiceClient.status()?.serial
        for ((source, version) in candidates().distinct()) {
            val xml = downloadCandidate(source, version, revocations) ?: continue
            val serial = IntegrityServiceClient.store(xml, source, version) ?: continue
            if (serial == active) continue
            return serial
        }
        return null
    }

    private fun downloadCandidate(source: String, version: String, revocations: String?): String? {
        val blob = httpGet("$KEYBOX_BASE/$source/$version") ?: return null
        val xml = decodeKeyboxBlob(blob) ?: return null
        val serial = firstCertificateSerial(xml) ?: return null
        if (isRevoked(revocations, serial)) {
            IntegrityServiceClient.markRevoked(serial)
            return null
        }
        return xml
    }

    /** workingEntries first, then working, then non-softbanned entries. */
    private fun catalogCandidates(catalog: String): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>()
        try {
            val json = JSONObject(catalog)
            json.optJSONArray("workingEntries")?.let { entries ->
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val source = entry.optString("source")
                    val version = entry.optString("version")
                    if (source.isNotEmpty() && version.isNotEmpty()) {
                        candidates.add(source to version)
                    }
                }
            }
            if (candidates.isEmpty()) {
                json.optJSONObject("working")?.let { working ->
                    val source = working.optString("source")
                    val version = working.optString("version")
                    if (source.isNotEmpty() && version.isNotEmpty()) {
                        candidates.add(source to version)
                    }
                }
            }
            if (candidates.isEmpty()) {
                json.optJSONArray("entries")?.let { entries ->
                    for (i in 0 until entries.length()) {
                        val entry = entries.optJSONObject(i) ?: continue
                        if (entry.optBoolean("softbanned") || entry.optBoolean("revoked")) continue
                        val source = entry.optString("source")
                        val version = entry.optString("version")
                        if (source.isNotEmpty() && version.isNotEmpty()) {
                            candidates.add(source to version)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return candidates
    }

    /**
     * Specter's decode_keybox_blob: map the shuffled alphabet back to the
     * standard one, then base64 decode to the keybox XML.
     */
    private fun decodeKeyboxBlob(blob: String): String? {
        return try {
            val mapped = StringBuilder(blob.length)
            for (c in blob) {
                val index = SHUFFLED_ALPHABET.indexOf(c)
                mapped.append(if (index >= 0) STD_ALPHABET[index] else c)
            }
            val cleaned = mapped.toString().replace("\\s".toRegex(), "")
            val xml = String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
            when {
                xml.contains("<Key") -> xml
                blob.trimStart().startsWith("<") -> blob
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun firstCertificateSerial(xml: String): String? {
        val match = Regex(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml) ?: return null
        return try {
            val der = Base64.decode(match.groupValues[1].replace("\\s".toRegex(), ""), Base64.DEFAULT)
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
            certificate.serialNumber.toString(16)
        } catch (t: Throwable) {
            null
        }
    }

    private fun isRevoked(revocations: String?, serialHex: String): Boolean {
        if (revocations.isNullOrEmpty()) return false
        return try {
            val entries = JSONObject(revocations).optJSONObject("entries") ?: return false
            val hex = serialHex.trimStart('0').lowercase()
            if (entries.has(hex)) return true
            val decimal = java.math.BigInteger(serialHex, 16).toString()
            entries.has(decimal)
        } catch (t: Throwable) {
            false
        }
    }

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TB520FUCustomFeatures")
            }
            connection = conn
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            }
        } catch (t: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
