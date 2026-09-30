/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.app.AlertDialog
import android.os.Bundle
import android.os.PowerManager
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/**
 * Main screen of the "Custom features" app: game performance and the Play Store
 * identity. The features live here (not in TB520FUParts) because they are
 * optional customizations; the device tree builds without them.
 */
class CustomFeaturesFragment : SettingsBasePreferenceFragment() {

    private lateinit var gamePerfPref: Preference
    private lateinit var spoofPref: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.custom_features_settings, rootKey)

        gamePerfPref = findPreference(KEY_GAME_PERF)!!

        // Play Store identity spoof; applied at boot, so the reboot dialog is
        // shown on every change.
        spoofPref = findPreference(KEY_SPOOF)!!
        spoofPref.isChecked = DeviceSpoof.enabled
        spoofPref.setOnPreferenceChangeListener { _, value ->
            DeviceSpoof.enabled = value as Boolean
            askReboot(R.string.spoof_reboot_message)
            true
        }
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
        const val KEY_SPOOF = "spoof_galaxy"
    }
}
