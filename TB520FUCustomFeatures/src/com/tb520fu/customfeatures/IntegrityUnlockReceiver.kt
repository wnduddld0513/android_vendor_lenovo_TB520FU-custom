/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Extra, cheap check of Google's revocation list and Specter's catalog when
 * the tablet is unlocked, throttled to one check every ten minutes. The
 * periodic job already checks every fifteen minutes; this makes sure a
 * freshly revoked keybox is swapped while the tablet is actually being used
 * (periodic jobs are deferred in Doze), and it costs two small requests.
 */
class IntegrityUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        if (!Integrity.enabled(Integrity.SPECTER)) return
        if (!hasNetwork(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) < THROTTLE_MS) return
        prefs.edit().putLong(KEY_LAST, now).apply()
        val pending = goAsync()
        Thread {
            try {
                KeyboxRenewal.renew(force = false, autoRotate = Integrity.autoRotate(context))
            } catch (t: Throwable) {
                // The next trigger tries again.
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * Nothing to do while offline: the periodic job waits for a network by
     * itself and the next unlock checks again.
     */
    private fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val PREFS = "integrity_unlock"
        const val KEY_LAST = "last_check"
        const val THROTTLE_MS = 10 * 60 * 1000L
    }
}
