/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.content.Context

/**
 * Cooldowns of the automatic keybox checks. Both default to thirty minutes:
 *
 *  - check period: how often the periodic job consults the catalog and the
 *    revocation list,
 *  - unlock cooldown: the shortest gap between two unlock triggered checks.
 *
 * Every unlock restarts the shared last-check mark, so the countdown starts
 * again with each unlock and a check happens once it has elapsed - either on
 * the next unlock or through the periodic job, whichever comes first.
 */
object CheckCooldown {

    const val DEFAULT_MINUTES = 30

    private const val PREFS = "integrity_cooldown"
    private const val KEY_PERIOD = "check_period_minutes"
    private const val KEY_UNLOCK = "unlock_minutes"
    private const val KEY_LAST = "last_check_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun periodMinutes(context: Context): Int =
        prefs(context).getInt(KEY_PERIOD, DEFAULT_MINUTES)

    fun unlockMinutes(context: Context): Int =
        prefs(context).getInt(KEY_UNLOCK, DEFAULT_MINUTES)

    fun setPeriodMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_PERIOD, minutes).apply()
        IntegrityJobService.schedule(context)
    }

    fun setUnlockMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_UNLOCK, minutes).apply()
    }

    /** True when the cooldown has elapsed since the last check or unlock. */
    fun due(context: Context, minutes: Int, now: Long = System.currentTimeMillis()): Boolean =
        now - prefs(context).getLong(KEY_LAST, 0L) >= minutes * 60_000L

    /** Restarts the countdown; called on every unlock and after every check. */
    fun restart(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST, now).apply()
    }
}
