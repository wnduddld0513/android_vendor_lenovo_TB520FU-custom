/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/**
 * Game performance: per-app CPU/GPU levels applied by
 * input/src/com/tb520fu/input/custom/GamePerfController.java only while that app is in the foreground.
 */
class GamePerfFragment : SettingsBasePreferenceFragment() {

    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var memCleanPref: SwitchPreferenceCompat
    private lateinit var appsCategory: PreferenceCategory

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.game_perf_settings, rootKey)
        val ctx = requireContext()

        mainSwitch = findPreference(KEY_MAIN)!!
        mainSwitch.isChecked = LenovoSettings.getInt(ctx, LenovoSettings.GAME_PERF, 0) != 0
        mainSwitch.addOnSwitchChangeListener { _, checked ->
            LenovoSettings.putInt(requireContext(), LenovoSettings.GAME_PERF, if (checked) 1 else 0)
            // No profile applies while the master switch is off, so the per-app
            // custom profiles are dropped with it.
            if (!checked) GameApps.clearCustom(requireContext())
            updateEnabled(checked)
        }

        memCleanPref = findPreference(KEY_MEM_CLEAN)!!
        memCleanPref.isChecked = LenovoSettings.getInt(ctx, LenovoSettings.GAME_MEM_CLEAN, 0) != 0
        memCleanPref.setOnPreferenceChangeListener { _, value ->
            LenovoSettings.putInt(requireContext(), LenovoSettings.GAME_MEM_CLEAN,
                if (value as Boolean) 1 else 0)
            true
        }

        appsCategory = findPreference(KEY_APPS)!!
        updateEnabled(mainSwitch.isChecked)
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(R.string.game_perf_title)
        refresh()
    }

    private fun updateEnabled(enabled: Boolean) {
        memCleanPref.isEnabled = enabled
        appsCategory.isEnabled = enabled
    }

    private fun refresh() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        GameApps.prune(ctx)

        appsCategory.removeAll()
        GameApps.load(ctx).forEach { (pkg, levels) ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            appsCategory.addPreference(Preference(ctx).apply {
                key = "app_$pkg"
                title = info?.loadLabel(pm) ?: pkg
                icon = info?.loadIcon(pm)
                summary = GameApps.describe(ctx, pkg, levels)
                setOnPreferenceClickListener { openApp(pkg); true }
            })
        }
        appsCategory.addPreference(Preference(ctx).apply {
            key = "app_add"
            title = getString(R.string.game_app_add)
            setIcon(R.drawable.ic_add)
            setOnPreferenceClickListener { pickApp(); true }
        })
    }

    /** Launchable apps not yet in the list; the chosen one starts as balanced/balanced. */
    private fun pickApp() {
        val ctx = requireContext().applicationContext
        AppPicker.pick(this, getString(R.string.game_app_add), GameApps.load(ctx).keys) { pkg ->
            GameApps.set(ctx, pkg,
                GameApps.Levels(GameApps.LEVEL_BALANCED, GameApps.LEVEL_BALANCED))
            openApp(pkg)
        }
    }

    private fun openApp(pkg: String) {
        parentFragmentManager.beginTransaction()
            .replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                GameAppFragment.newInstance(pkg),
            )
            .addToBackStack(null)
            .commit()
    }

    private companion object {
        const val KEY_MAIN = "game_perf_enabled"
        const val KEY_MEM_CLEAN = "game_mem_clean"
        const val KEY_APPS = "game_apps"
    }
}
