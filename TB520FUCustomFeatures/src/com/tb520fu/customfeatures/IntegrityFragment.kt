/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.app.AlertDialog
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.json.JSONObject

/**
 * Management screens of the Integrity features; one fragment, one screen:
 *
 *  - keybox  the Specter port: state, automatic renewal, renew now, delete
 *  - teesim  the TEESimulator-RS port: state and the per-app target list
 *  - pif     the PlayIntegrityFix port: fingerprint data state, refresh, clear
 *
 * The switches on the main screen only arm the features (a restart applies
 * them); everything on these screens acts live through the system_server
 * keybox service.
 */
class IntegrityFragment : SettingsBasePreferenceFragment() {

    private val screen: String
        get() = requireArguments().getString(ARG_SCREEN) ?: SCREEN_KEYBOX

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        when (screen) {
            SCREEN_TEESIM -> setPreferencesFromResource(R.xml.integrity_teesim, rootKey)
            SCREEN_PIF -> setPreferencesFromResource(R.xml.integrity_pif, rootKey)
            else -> setPreferencesFromResource(R.xml.integrity_keybox, rootKey)
        }
        when (screen) {
            SCREEN_TEESIM -> bindTeesim()
            SCREEN_PIF -> bindPif()
            else -> bindKeybox()
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(
            when (screen) {
                SCREEN_TEESIM -> R.string.teesim_title
                SCREEN_PIF -> R.string.pif_title
                else -> R.string.keybox_title
            }
        )
        refresh()
    }

    private fun refresh() {
        when (screen) {
            SCREEN_TEESIM -> refreshTeesim()
            SCREEN_PIF -> refreshPif()
            else -> refreshKeybox()
        }
    }

    //
    // Keybox (Specter port)
    //

    private fun bindKeybox() {
        findPreference<SwitchPreferenceCompat>("keybox_auto_rotate")!!.setOnPreferenceChangeListener { _, value ->
            Integrity.setAutoRotate(requireContext(), value as Boolean)
            true
        }
        findPreference<Preference>("keybox_renew")!!.setOnPreferenceClickListener {
            renewKeybox()
            true
        }
        findPreference<Preference>("keybox_clear")!!.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.keybox_clear_title)
                .setMessage(R.string.keybox_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    IntegrityServiceClient.clear()
                    Toast.makeText(requireContext(), R.string.keybox_cleared, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    private fun refreshKeybox() {
        val status = IntegrityServiceClient.status()
        val state = findPreference<Preference>("keybox_state")!!
        val source = findPreference<Preference>("keybox_source")!!
        val serial = findPreference<Preference>("keybox_serial")!!
        val pool = findPreference<Preference>("keybox_pool")!!
        val auto = findPreference<SwitchPreferenceCompat>("keybox_auto") ?: return
        val rotate = findPreference<SwitchPreferenceCompat>("keybox_auto_rotate") ?: return

        rotate.setOnPreferenceChangeListener(null)
        rotate.isChecked = Integrity.autoRotate(requireContext())
        rotate.setOnPreferenceChangeListener { _, value ->
            Integrity.setAutoRotate(requireContext(), value as Boolean)
            true
        }

        auto.setOnPreferenceChangeListener(null)
        auto.isChecked = Integrity.enabled(Integrity.SPECTER)
        auto.setOnPreferenceChangeListener { _, value ->
            val enabled = value as Boolean
            Integrity.set(Integrity.SPECTER, enabled)
            if (enabled) IntegrityJobService.schedule(requireContext())
            else IntegrityJobService.cancel(requireContext())
            Integrity.askReboot(
                this,
                if (enabled) R.string.integrity_reboot_message
                else R.string.integrity_reboot_off_message,
            )
            true
        }

        if (status == null) {
            state.summary = getString(R.string.keybox_state_unavailable)
            source.summary = "—"
            serial.summary = "—"
            pool.summary = "—"
            return
        }
        val entries = IntegrityServiceClient.pool()
        val activeRevoked = entries.any { it.active && it.revoked }
        state.summary = when {
            !status.installed -> getString(R.string.keybox_state_none)
            activeRevoked -> getString(R.string.keybox_state_revoked)
            else -> getString(R.string.keybox_state_installed)
        }
        source.summary = listOfNotNull(
            status.source?.takeIf { it.isNotEmpty() },
            status.version?.takeIf { it.isNotEmpty() },
        ).joinToString(" ").ifEmpty { "—" }
        serial.summary = status.serial?.takeIf { it.isNotEmpty() } ?: "—"
        pool.summary = if (status.poolSpare > 0 || status.revokedCount > 0) {
            getString(R.string.keybox_pool_summary, status.poolSpare, status.revokedCount)
        } else {
            getString(R.string.keybox_pool_none)
        }
    }

    private fun renewKeybox() {
        val renew = findPreference<Preference>("keybox_renew") ?: return
        val autoRotate = Integrity.autoRotate(requireContext())
        renew.isEnabled = false
        renew.summary = getString(R.string.keybox_renewing)
        Thread {
            val result = try {
                KeyboxRenewal.renew(force = true, autoRotate = autoRotate)
            } catch (t: Throwable) {
                KeyboxRenewal.Result.Failed(t.message ?: t.javaClass.simpleName)
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                renew.isEnabled = true
                var message = when (result) {
                    is KeyboxRenewal.Result.Ok ->
                        getString(R.string.keybox_renew_ok, result.source, result.version)
                    is KeyboxRenewal.Result.Failed ->
                        getString(R.string.keybox_renew_failed) + ": " + result.reason
                }
                if (result is KeyboxRenewal.Result.Ok && result.activeRevoked) {
                    message += " · " + getString(R.string.keybox_state_revoked)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    //
    // TEESimulator
    //

    private fun bindTeesim() {
        findPreference<SwitchPreferenceCompat>("teesim_auto")!!.setOnPreferenceChangeListener { _, value ->
            IntegrityServiceClient.setAutoTarget(value as Boolean)
            refresh()
            true
        }
        findPreference<Preference>("teesim_add_all")!!.setOnPreferenceClickListener {
            val addAll = findPreference<Preference>("teesim_add_all") ?: return@setOnPreferenceClickListener true
            addAll.isEnabled = false
            Thread {
                val added = IntegrityServiceClient.addAllInstalled()
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    addAll.isEnabled = true
                    if (added >= 0) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.teesim_add_all_done, added),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    refresh()
                }
            }.start()
            true
        }
        findPreference<Preference>("teesim_add")!!.setOnPreferenceClickListener {
            val status = IntegrityServiceClient.status()
            AppPicker.pick(
                this,
                getString(R.string.teesim_target_add),
                (status?.targets ?: emptyList()).toSet(),
            ) { pkg ->
                val current = IntegrityServiceClient.status()
                val targets = (current?.targets ?: emptyList()).toMutableList()
                if (pkg !in targets) targets.add(pkg)
                IntegrityServiceClient.setTargets(targets, current?.allTargets ?: false)
                refresh()
            }
            true
        }
    }

    private fun refreshTeesim() {
        val status = IntegrityServiceClient.status()
        val state = findPreference<Preference>("teesim_state")!!
        val keybox = findPreference<Preference>("teesim_keybox")!!
        val all = findPreference<SwitchPreferenceCompat>("teesim_all")!!
        val auto = findPreference<SwitchPreferenceCompat>("teesim_auto")!!
        val category = findPreference<PreferenceCategory>("teesim_target_list")!!

        if (status == null) {
            state.summary = getString(R.string.keybox_state_unavailable)
            keybox.summary = "—"
            all.isEnabled = false
            auto.isEnabled = false
            category.removeAll()
            return
        }
        all.isEnabled = true
        auto.isEnabled = true
        state.summary = if (status.enabled) getString(R.string.teesim_on)
        else getString(R.string.teesim_off)
        keybox.summary = if (status.installed) getString(R.string.keybox_state_installed)
        else getString(R.string.keybox_state_none)

        auto.setOnPreferenceChangeListener(null)
        auto.isChecked = status.autoTarget
        auto.setOnPreferenceChangeListener { _, value ->
            IntegrityServiceClient.setAutoTarget(value as Boolean)
            refresh()
            true
        }

        all.setOnPreferenceChangeListener(null)
        all.isChecked = status.allTargets
        all.setOnPreferenceChangeListener { _, value ->
            val current = IntegrityServiceClient.status()
            IntegrityServiceClient.setTargets(current?.targets ?: emptyList(), value as Boolean)
            refresh()
            true
        }

        category.removeAll()
        for (pkg in status.targets) {
            category.addPreference(Preference(requireContext()).apply {
                title = pkg
                summary = getString(R.string.teesim_target_remove)
                setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(pkg)
                        .setMessage(R.string.teesim_target_remove_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val current = IntegrityServiceClient.status()
                            val targets = (current?.targets ?: emptyList()).toMutableList()
                            targets.remove(pkg)
                            IntegrityServiceClient.setTargets(targets, current?.allTargets ?: false)
                            refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            })
        }
    }

    //
    // PIF
    //

    private fun bindPif() {
        findPreference<Preference>("pif_refresh")!!.setOnPreferenceClickListener {
            refreshPifData()
            true
        }
        findPreference<Preference>("pif_clear")!!.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pif_clear_title)
                .setMessage(R.string.pif_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    IntegrityServiceClient.clearPif()
                    Toast.makeText(requireContext(), R.string.pif_cleared, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    private fun refreshPif() {
        val resolver = requireContext().contentResolver
        val user = Settings.Secure.getString(resolver, Settings.Secure.PIF_DATA)
        val fetched = Settings.Secure.getString(resolver, Settings.Secure.FETCHED_PIF)
        val data = user ?: fetched
        val state = findPreference<Preference>("pif_state")!!
        val patch = findPreference<Preference>("pif_patch")!!
        val fingerprint = findPreference<Preference>("pif_fingerprint")!!

        state.summary = when {
            data.isNullOrEmpty() -> getString(R.string.pif_state_none)
            user != null -> getString(R.string.pif_state_user)
            else -> getString(R.string.pif_state_fetched)
        }
        val json = try {
            JSONObject(data ?: "")
        } catch (t: Throwable) {
            null
        }
        patch.summary = json?.optString("SECURITY_PATCH")?.takeIf { it.isNotEmpty() } ?: "—"
        fingerprint.summary = json?.optString("FINGERPRINT")?.takeIf { it.isNotEmpty() } ?: "—"
    }

    private fun refreshPifData() {
        val refresh = findPreference<Preference>("pif_refresh") ?: return
        refresh.isEnabled = false
        refresh.summary = getString(R.string.pif_refreshing)
        Thread {
            val json = try {
                KeyboxRenewal.fetchPif()
            } catch (t: Throwable) {
                null
            }
            val installed = json != null && IntegrityServiceClient.putPif(json)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                refresh.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    if (installed) R.string.pif_refresh_ok else R.string.pif_refresh_failed,
                    Toast.LENGTH_LONG,
                ).show()
                refresh()
            }
        }.start()
    }

    companion object {
        const val ARG_SCREEN = "screen"
        const val SCREEN_KEYBOX = "keybox"
        const val SCREEN_TEESIM = "teesim"
        const val SCREEN_PIF = "pif"

        fun newInstance(screen: String): IntegrityFragment =
            IntegrityFragment().apply {
                arguments = Bundle().apply { putString(ARG_SCREEN, screen) }
            }
    }
}
