/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.os.SystemProperties

/**
 * Hides the real installer of sideloaded apps from other apps: while this is
 * on, PackageManager reports the Play Store as the installer, so apps that
 * require a Play install source (for example Notein) keep working.
 *
 * The framework reads the property live, so the switch applies immediately
 * (no restart) and it defaults to off.
 */
object InstallerSpoof {

    const val PROP = "persist.sys.tb520fu.spoof_installer"

    var enabled: Boolean
        get() = SystemProperties.getBoolean(PROP, false)
        set(value) = SystemProperties.set(PROP, if (value) "1" else "0")
}
