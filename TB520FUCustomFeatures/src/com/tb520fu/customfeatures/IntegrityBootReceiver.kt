/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Keeps the keybox renewal job in sync with the keybox switch across boots.
 * The JobScheduler job is persisted, this is just a safety net for the case
 * where it was dropped (for example after an app update).
 */
class IntegrityBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Integrity.enabled(Integrity.KEYBOX)) {
            IntegrityJobService.schedule(context)
        } else {
            IntegrityJobService.cancel(context)
        }
    }
}
