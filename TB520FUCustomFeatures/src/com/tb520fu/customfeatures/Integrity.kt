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
 * Play Integrity spoofing switches ("Custom features" > Integrity).
 *
 * The three switches are kept in persist properties; init.tb520fu.integrity.rc
 * copies them to sys.tb520fu.integrity_* on boot and only the snapshots are
 * read, by the framework hooks and by the keybox service in system_server, so
 * a change needs a restart. After that first restart keybox and fingerprint
 * data updates apply live, without another one.
 *
 *  - [SPECTER] automatic keybox renewal (the Specter catalog downloader)
 *  - [TEESIM]  keybox attestation spoofing (the TEESimulator-RS port)
 *  - [PIF]     GMS fingerprint spoofing and the DroidGuard key block (PIF)
 */
object Integrity {

    const val SPECTER = "persist.sys.tb520fu.integrity_specter"
    const val TEESIM = "persist.sys.tb520fu.integrity_teesim"
    const val PIF = "persist.sys.tb520fu.integrity_pif"

    /** Keybox auto swap on revocation; on by default, applied live. */
    const val AUTO_ROTATE = "tb520fu_integrity_auto_rotate"

    fun autoRotate(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, AUTO_ROTATE, 1) != 0

    fun setAutoRotate(context: Context, enabled: Boolean) {
        Settings.Global.putInt(context.contentResolver, AUTO_ROTATE, if (enabled) 1 else 0)
    }

    fun enabled(prop: String): Boolean = android.os.SystemProperties.getBoolean(prop, false)

    fun set(prop: String, value: Boolean) {
        android.os.SystemProperties.set(prop, if (value) "1" else "0")
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
