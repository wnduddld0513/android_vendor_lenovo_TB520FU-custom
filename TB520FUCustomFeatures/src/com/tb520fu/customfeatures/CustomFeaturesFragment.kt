/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.app.AlertDialog
import android.os.Bundle
import android.os.PowerManager
import androidx.preference.Preference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/**
 * Main screen of the "Custom features" app: game performance and the Play
 * Integrity Fix switch. The features live here (not in
 * TB520FUParts) because they are optional customizations; the device tree
 * builds without them.
 */
class CustomFeaturesFragment : SettingsBasePreferenceFragment() {

    private lateinit var gamePerfPref: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.custom_features_settings, rootKey)

        gamePerfPref = findPreference(KEY_GAME_PERF)!!

        bindIntegrity()
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(R.string.app_name)
        gamePerfPref.summary = getString(
            if (LenovoSettings.getInt(requireContext(), LenovoSettings.GAME_PERF, 0) != 0) {
                R.string.game_perf_on
            } else {
                R.string.game_perf_off
            }
        )
    }

    /**
     * The single "Play Integrity Fix" switch arms the whole stack (keybox
     * renewal, TEE simulator, PIF); it is applied at boot (see Integrity) and
     * on by default on a fresh install. A tap on the title opens the
     * management screen instead of toggling.
     */
    private fun bindIntegrity() {
        val pref = findPreference<TitleClickSwitchPreference>(KEY_INTEGRITY_FIX)!!
        pref.isChecked = Integrity.fixEnabled()
        pref.onTitleClick = { openScreen() }
        pref.setOnPreferenceChangeListener { _, value ->
            val enabled = value as Boolean
            Integrity.setFix(enabled)
            if (enabled) {
                IntegrityJobService.schedule(requireContext())
            } else {
                IntegrityJobService.cancel(requireContext())
            }
            askReboot(
                if (enabled) {
                    R.string.integrity_reboot_message
                } else {
                    R.string.integrity_reboot_off_message
                },
            )
            true
        }
    }

    private fun openScreen() {
        parentFragmentManager.beginTransaction()
            .replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                IntegrityFragment(),
                "integrity_fix",
            )
            .addToBackStack(null)
            .commit()
    }

    private fun askReboot(message: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reboot_title)
            .setMessage(message)
            .setPositiveButton(R.string.reboot_now) { _, _ ->
                requireContext().getSystemService(PowerManager::class.java).reboot(null)
            }
            .setNegativeButton(R.string.reboot_later, null)
            .show()
    }

    private companion object {
        const val KEY_GAME_PERF = "game_perf"
        const val KEY_INTEGRITY_FIX = "integrity_fix"
    }
}
