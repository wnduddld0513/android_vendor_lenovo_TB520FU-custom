/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Per-app game performance levels, stored for input/src/com/tb520fu/input/custom/GamePerfController.java
 * as Settings.Global tb520fu_game_perf_apps = "pkg:cpu:gpu;pkg:cpu:gpu".
 * Levels: 0 power saving, 1 balanced (80 % of the maximum), 2 default (stock
 * limits), 3 custom (per-app percentages, one profile per app).
 *
 * The custom percentages live in a separate setting,
 * tb520fu_game_perf_custom = "pkg:single:multi:gpu;pkg:...", so an app without
 * the custom level has no data there.
 */
object GameApps {
    const val LEVEL_POWER_SAVING = 0
    const val LEVEL_BALANCED = 1
    const val LEVEL_DEFAULT = 2
    const val LEVEL_CUSTOM = 3
    val LEVELS = intArrayOf(LEVEL_POWER_SAVING, LEVEL_BALANCED, LEVEL_DEFAULT, LEVEL_CUSTOM)

    /** Custom profile bounds; mirrored by input/src/com/tb520fu/input/custom/GamePerfController.java. */
    const val CUSTOM_MIN_PERCENT = 30
    const val CUSTOM_MAX_PERCENT = 100
    const val CUSTOM_STEP_PERCENT = 10
    const val CUSTOM_DEFAULT_PERCENT = 80

    data class Levels(val cpu: Int, val gpu: Int)

    /** Custom limits in percent of the cluster maximum / the top GPU clock. */
    data class Custom(val cpu: Int, val gpu: Int) {
        companion object {
            val DEFAULT = Custom(CUSTOM_DEFAULT_PERCENT, CUSTOM_DEFAULT_PERCENT)
        }
    }

    fun isValidPercent(percent: Int): Boolean =
        percent in CUSTOM_MIN_PERCENT..CUSTOM_MAX_PERCENT &&
            (percent - CUSTOM_MIN_PERCENT) % CUSTOM_STEP_PERCENT == 0

    /** Package name to levels, in the order the apps were added. */
    fun load(ctx: Context): LinkedHashMap<String, Levels> {
        val map = LinkedHashMap<String, Levels>()
        LenovoSettings.getString(ctx, LenovoSettings.GAME_PERF_APPS, "")
            .split(';')
            .forEach { entry ->
                val f = entry.split(':')
                if (f.size != 3 || f[0].isEmpty()) return@forEach
                val cpu = f[1].toIntOrNull()?.coerceIn(LEVEL_POWER_SAVING, LEVEL_CUSTOM)
                    ?: return@forEach
                val gpu = f[2].toIntOrNull()?.coerceIn(LEVEL_POWER_SAVING, LEVEL_CUSTOM)
                    ?: return@forEach
                map[f[0]] = Levels(cpu, gpu)
            }
        return map
    }

    /** Saves the list; an empty list deletes the setting instead of leaving an empty value. */
    fun save(ctx: Context, apps: Map<String, Levels>) {
        if (apps.isEmpty()) {
            ctx.contentResolver.call(
                Settings.Global.CONTENT_URI,
                Settings.CALL_METHOD_DELETE_GLOBAL,
                LenovoSettings.GAME_PERF_APPS,
                null,
            )
        } else {
            LenovoSettings.putString(
                ctx,
                LenovoSettings.GAME_PERF_APPS,
                apps.entries.joinToString(";") { "${it.key}:${it.value.cpu}:${it.value.gpu}" },
            )
        }
    }

    fun get(ctx: Context, pkg: String) = load(ctx)[pkg]

    fun set(ctx: Context, pkg: String, levels: Levels) =
        save(ctx, load(ctx).apply { put(pkg, levels) })

    /** Package name to custom profile, in the order the apps were added. */
    fun loadCustom(ctx: Context): LinkedHashMap<String, Custom> {
        val map = LinkedHashMap<String, Custom>()
        LenovoSettings.getString(ctx, LenovoSettings.GAME_PERF_CUSTOM, "")
            .split(';')
            .forEach { entry ->
                val f = entry.split(':')
                if (f.size != 4 || f[0].isEmpty()) return@forEach
                // One CPU percentage for every cluster; the setting keeps the
                // single/multi pair of GamePerfController, older profiles
                // (different values) take the multi core one.
                val cpu = f[2].toIntOrNull()?.takeIf(::isValidPercent) ?: return@forEach
                val gpu = f[3].toIntOrNull()?.takeIf(::isValidPercent) ?: return@forEach
                map[f[0]] = Custom(cpu, gpu)
            }
        return map
    }

    fun saveCustom(ctx: Context, customs: Map<String, Custom>) {
        if (customs.isEmpty()) {
            ctx.contentResolver.call(
                Settings.Global.CONTENT_URI,
                Settings.CALL_METHOD_DELETE_GLOBAL,
                LenovoSettings.GAME_PERF_CUSTOM,
                null,
            )
        } else {
            LenovoSettings.putString(
                ctx,
                LenovoSettings.GAME_PERF_CUSTOM,
                customs.entries.joinToString(";") {
                    "${it.key}:${it.value.cpu}:${it.value.cpu}:${it.value.gpu}"
                },
            )
        }
    }

    fun getCustom(ctx: Context, pkg: String) = loadCustom(ctx)[pkg]

    /** The saved profile or the defaults, so the sliders always have a value. */
    fun custom(ctx: Context, pkg: String) = getCustom(ctx, pkg) ?: Custom.DEFAULT

    fun setCustom(ctx: Context, pkg: String, custom: Custom) =
        saveCustom(ctx, loadCustom(ctx).apply { put(pkg, custom) })

    /** Drops every custom profile, used when the master switch is turned off. */
    fun clearCustom(ctx: Context) = saveCustom(ctx, emptyMap())

    fun remove(ctx: Context, pkg: String) {
        save(ctx, load(ctx).apply { remove(pkg) })
        saveCustom(ctx, loadCustom(ctx).apply { remove(pkg) })
    }

    /** Drops apps that are no longer installed, and their custom profiles. */
    fun prune(ctx: Context) {
        val apps = load(ctx)
        val pm = ctx.packageManager
        val installed = apps.filterKeys { pkg ->
            runCatching { pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)) }
                .isSuccess
        }
        if (installed.size != apps.size) save(ctx, installed)
        val customs = loadCustom(ctx)
        val kept = customs.filterKeys { it in installed }
        if (kept.size != customs.size) saveCustom(ctx, kept)
    }

    fun levelName(ctx: Context, level: Int): String = when (level) {
        LEVEL_CUSTOM -> ctx.getString(R.string.game_custom_level)
        else -> ctx.resources.getStringArray(R.array.game_level_entries)
            .getOrElse(level.coerceIn(LEVEL_POWER_SAVING, LEVEL_DEFAULT)) { "" }
    }

    /** "CPU Balanced · GPU Default" or, for the custom level, its percentages. */
    fun describe(ctx: Context, pkg: String, levels: Levels): String {
        val custom = custom(ctx, pkg)
        val cpu = if (levels.cpu == LEVEL_CUSTOM) {
            ctx.getString(R.string.game_custom_cpu_summary, custom.cpu)
        } else {
            levelName(ctx, levels.cpu)
        }
        val gpu = if (levels.gpu == LEVEL_CUSTOM) {
            ctx.getString(R.string.game_custom_gpu_summary, custom.gpu)
        } else {
            levelName(ctx, levels.gpu)
        }
        return ctx.getString(R.string.game_app_levels, cpu, gpu)
    }

    fun label(ctx: Context, pkg: String): CharSequence =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager) }
            .getOrDefault(pkg)
}
