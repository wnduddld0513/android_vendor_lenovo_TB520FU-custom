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
 * Main screen of the "Custom features" app: game performance, the Play
 * Integrity Fix switch and the installer spoof switch. The features live here
 * (not in TB520FUParts) because they are optional customizations; the device
 * tree builds without them.
 *
 * The switches only switch their feature on and off; the management screens
 * are opened from separate rows (the same pattern as the Lenovo features
 * app), so a tap can never toggle a feature by accident while navigating.
 */
class CustomFeaturesFragment : SettingsBasePreferenceFragment() {

    private lateinit var gamePerfPref: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.custom_features_settings, rootKey)

        gamePerfPref = findPreference(KEY_GAME_PERF)!!

        bindIntegrity()
        bindIntegrityEntry()
        bindInstallerSpoof()
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
     * on by default on a fresh install. The switch only toggles the feature;
     * [bindIntegrityEntry] opens the management screen.
     */
    private fun bindIntegrity() {
        val pref = findPreference<SwitchPreferenceCompat>(KEY_INTEGRITY_FIX)!!
        pref.isChecked = Integrity.fixEnabled()
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

    /** Separate row that opens the Play Integrity management screen. */
    private fun bindIntegrityEntry() {
        findPreference<Preference>(KEY_INTEGRITY_SETTINGS)!!.setOnPreferenceClickListener {
            openScreen()
            true
        }
    }

    /**
     * Reports the Play Store as the installer of sideloaded apps (framework
     * side, see InstallerSpoof); the property is read live, so no restart
     * prompt.
     */
    private fun bindInstallerSpoof() {
        val pref = findPreference<SwitchPreferenceCompat>(KEY_INSTALLER_SPOOF)!!
        pref.isChecked = InstallerSpoof.enabled
        pref.setOnPreferenceChangeListener { _, value ->
            InstallerSpoof.enabled = value as Boolean
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
        const val KEY_INTEGRITY_SETTINGS = "integrity_settings"
        const val KEY_INSTALLER_SPOOF = "installer_spoof"
    }
}
