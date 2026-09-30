/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.os.Parcel
import android.os.ServiceManager

/**
 * Binder client of the "tb520fu.keybox" service published by
 * tb520fu-input-custom.jar in system_server (the TEESimulator-RS / Specter
 * port). The app is the only admin user of the service; the attestation
 * patching path is used by the framework, not here.
 */
object IntegrityServiceClient {

    private const val SERVICE = "tb520fu.keybox"

    private const val TRANSACT_STATUS = 1
    private const val TRANSACT_INSTALL = 3
    private const val TRANSACT_CLEAR = 4
    private const val TRANSACT_SET_TARGETS = 5
    private const val TRANSACT_PUT_PIF = 6
    private const val TRANSACT_CLEAR_PIF = 7
    private const val TRANSACT_STORE = 8
    private const val TRANSACT_POOL = 9
    private const val TRANSACT_ROTATE = 10
    private const val TRANSACT_MARK_REVOKED = 11
    private const val TRANSACT_SET_AUTO_TARGET = 12
    private const val TRANSACT_ADD_ALL_INSTALLED = 13

    data class Status(
        val enabled: Boolean,
        val installed: Boolean,
        val generation: Long,
        val serial: String?,
        val source: String?,
        val version: String?,
        val allTargets: Boolean,
        val targets: List<String>,
        val autoTarget: Boolean,
        val poolSpare: Int,
        val revokedCount: Int,
    )

    data class PoolEntry(
        val source: String?,
        val version: String?,
        val serial: String?,
        val active: Boolean,
        val revoked: Boolean,
    )

    data class Rotated(
        val serial: String?,
        val source: String?,
        val version: String?,
    )

    private fun binder() = try {
        ServiceManager.getService(SERVICE)
    } catch (t: Throwable) {
        null
    }

    fun status(): Status? {
        val b = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!b.transact(TRANSACT_STATUS, data, reply, 0)) return null
            reply.setDataPosition(0)
            Status(
                enabled = reply.readInt() != 0,
                installed = reply.readInt() != 0,
                generation = reply.readLong(),
                serial = reply.readString(),
                source = reply.readString(),
                version = reply.readString(),
                allTargets = reply.readInt() != 0,
                targets = reply.createStringArray()?.toList() ?: emptyList(),
                autoTarget = reply.readInt() != 0,
                poolSpare = reply.readInt(),
                revokedCount = reply.readInt(),
            )
        } catch (t: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun pool(): List<PoolEntry> {
        val b = binder() ?: return emptyList()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!b.transact(TRANSACT_POOL, data, reply, 0)) return emptyList()
            reply.setDataPosition(0)
            val count = reply.readInt()
            val entries = ArrayList<PoolEntry>(count)
            for (i in 0 until count) {
                entries.add(
                    PoolEntry(
                        source = reply.readString(),
                        version = reply.readString(),
                        serial = reply.readString(),
                        active = reply.readInt() != 0,
                        revoked = reply.readInt() != 0,
                    )
                )
            }
            entries
        } catch (t: Throwable) {
            emptyList()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Installs and activates a keybox. */
    fun install(xml: String, source: String, version: String): String? =
        transactString(TRANSACT_INSTALL, xml, source, version)

    /** Stores a keybox in the spare pool without activating it. */
    fun store(xml: String, source: String, version: String): String? =
        transactString(TRANSACT_STORE, xml, source, version)

    private fun transactString(code: Int, xml: String, source: String, version: String): String? {
        val b = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(xml)
            data.writeString(source)
            data.writeString(version)
            if (!b.transact(code, data, reply, 0)) return null
            reply.setDataPosition(0)
            if (reply.readInt() == 0) reply.readString() else null
        } catch (t: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun clear(): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            b.transact(TRANSACT_CLEAR, data, reply, 0) && reply.run {
                setDataPosition(0)
                readInt() == 0
            }
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Swaps in the next spare keybox that is not revoked; null when none is left. */
    fun rotate(): Rotated? {
        val b = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!b.transact(TRANSACT_ROTATE, data, reply, 0)) return null
            reply.setDataPosition(0)
            if (reply.readInt() != 0) null
            else Rotated(reply.readString(), reply.readString(), reply.readString())
        } catch (t: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun markRevoked(serial: String?): Boolean {
        if (serial.isNullOrEmpty()) return false
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(serial)
            b.transact(TRANSACT_MARK_REVOKED, data, reply, 0) && reply.run {
                setDataPosition(0)
                readInt() == 0
            }
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun setAutoTarget(enabled: Boolean): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInt(if (enabled) 1 else 0)
            b.transact(TRANSACT_SET_AUTO_TARGET, data, reply, 0) && reply.run {
                setDataPosition(0)
                readInt() == 0
            }
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Adds every installed third-party app; returns how many were added. */
    fun addAllInstalled(): Int {
        val b = binder() ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!b.transact(TRANSACT_ADD_ALL_INSTALLED, data, reply, 0)) -1
            else reply.run {
                setDataPosition(0)
                readInt()
            }
        } catch (t: Throwable) {
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun setTargets(packages: List<String>, all: Boolean): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(packages.toTypedArray())
            data.writeInt(if (all) 1 else 0)
            b.transact(TRANSACT_SET_TARGETS, data, reply, 0) && reply.run {
                setDataPosition(0)
                readInt() == 0
            }
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun putPif(json: String): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(json)
            b.transact(TRANSACT_PUT_PIF, data, reply, 0) && reply.run {
                setDataPosition(0)
                readInt() == 0
            }
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun clearPif(): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            b.transact(TRANSACT_CLEAR_PIF, data, reply, 0) && reply.run {
                setDataPosition(0)
                readInt() == 0
            }
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
