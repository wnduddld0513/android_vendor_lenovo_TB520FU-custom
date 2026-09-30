/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.os.SystemProperties

/**
 * Optional identity spoof for the Play Store (PropImitationHooks). The choice
 * is kept in [PROP]; init.tb520fu.spoof.rc copies it to sys.tb520fu.spoof_galaxy
 * on boot, and the framework reads only that snapshot, so flipping the switch
 * takes effect after a restart.
 */
object DeviceSpoof {

    const val PROP = "persist.sys.tb520fu.spoof_galaxy"

    var enabled: Boolean
        get() = SystemProperties.getBoolean(PROP, false)
        set(value) = SystemProperties.set(PROP, if (value) "1" else "0")
}
