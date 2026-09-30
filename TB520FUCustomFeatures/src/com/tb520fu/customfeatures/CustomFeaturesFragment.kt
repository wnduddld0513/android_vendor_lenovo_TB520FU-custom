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
 * Main screen of the "Custom features" app: game performance, the Play Store
 * identity and the Play Integrity Fix switch. The features live here (not in
 * TB520FUParts) because they are optional customizations; the device tree
 * builds without them.
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

        bindIntegrity()
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(R.string.app_name)
        // The Galaxy identity may have been turned off from the management
        // screen (it conflicts with Play Integrity Fix).
        spoofPref.isChecked = DeviceSpoof.enabled
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
            // The Galaxy Play Store identity spoofs another device and makes
            // the Play Integrity verdicts fail; turn it off with the fix.
            var galaxyOff = false
            if (enabled && DeviceSpoof.enabled) {
                DeviceSpoof.enabled = false
                spoofPref.isChecked = false
                galaxyOff = true
            }
            askReboot(
                when {
                    !enabled -> R.string.integrity_reboot_off_message
                    galaxyOff -> R.string.integrity_reboot_galaxy_off_message
                    else -> R.string.integrity_reboot_message
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
        const val KEY_SPOOF = "spoof_galaxy"
        const val KEY_INTEGRITY_FIX = "integrity_fix"
    }
}
