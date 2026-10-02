/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.app.AlertDialog
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.fragment.app.Fragment

/**
 * Play Integrity Fix. One master switch ("Play Integrity Fix" in "Custom
 * Tweaks") arms all three:
 *
 *  - [KEYBOX]  automatic keybox renewal from the keybox catalog
 *  - [TEESIM]  keybox attestation spoofing for the target apps
 *  - [PIF]     GMS fingerprint spoofing and the key block
 *
 * The switches are kept in persist properties; init.tb520fu.integrity.rc
 * copies them to sys.tb520fu.integrity_* on boot and only the snapshots are
 * read, so a change needs a restart. On a fresh install, and after a factory
 * reset, the properties are unset and the feature is off by default; the app
 * writes an explicit 0 or 1 as soon as the switch is used.
 *
 * After that first restart keybox and fingerprint data updates apply live,
 * without another one.
 */
object Integrity {

    const val KEYBOX = "persist.sys.tb520fu.integrity_keybox"
    const val TEESIM = "persist.sys.tb520fu.integrity_teesim"
    const val PIF = "persist.sys.tb520fu.integrity_pif"

    /** Keybox auto swap on revocation; on by default, applied live. */
    const val AUTO_ROTATE = "tb520fu_integrity_auto_rotate"

    /** Unset means the factory default: off. */
    fun enabled(prop: String): Boolean = android.os.SystemProperties.getBoolean(prop, false)

    fun set(prop: String, value: Boolean) {
        android.os.SystemProperties.set(prop, if (value) "1" else "0")
    }

    /** The master switch: on only when all three features are on. */
    fun fixEnabled(): Boolean = enabled(KEYBOX) && enabled(TEESIM) && enabled(PIF)

    fun setFix(value: Boolean) {
        set(KEYBOX, value)
        set(TEESIM, value)
        set(PIF, value)
    }

    fun autoRotate(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, AUTO_ROTATE, 1) != 0

    fun setAutoRotate(context: Context, enabled: Boolean) {
        Settings.Global.putInt(context.contentResolver, AUTO_ROTATE, if (enabled) 1 else 0)
    }

    /** Reboot prompt shown after every switch change; the change is applied at boot. */
    fun askReboot(fragment: Fragment, message: Int) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.reboot_title)
            .setMessage(message)
            .setPositiveButton(R.string.reboot_now) { _, _ ->
                fragment.requireContext().getSystemService(PowerManager::class.java).reboot(null)
            }
            .setNegativeButton(R.string.reboot_later, null)
            .show()
    }
}
