/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.content.Context
import android.provider.Settings

/**
 * Settings.Global keys applied by tb520fu-input in system_server
 * (input/src/com/tb520fu/input/custom/GamePerfController.java in this
 * repository).
 */
object LenovoSettings {
    const val GAME_PERF = "tb520fu_game_perf"
    const val GAME_PERF_APPS = "tb520fu_game_perf_apps"
    const val GAME_PERF_CUSTOM = "tb520fu_game_perf_custom"
    const val GAME_MEM_CLEAN = "tb520fu_game_mem_clean"

    fun getInt(ctx: Context, key: String, def: Int) =
        Settings.Global.getInt(ctx.contentResolver, key, def)

    fun putInt(ctx: Context, key: String, value: Int) =
        Settings.Global.putInt(ctx.contentResolver, key, value)

    fun getString(ctx: Context, key: String, def: String) =
        Settings.Global.getString(ctx.contentResolver, key) ?: def

    fun putString(ctx: Context, key: String, value: String) =
        Settings.Global.putString(ctx.contentResolver, key, value)
}
