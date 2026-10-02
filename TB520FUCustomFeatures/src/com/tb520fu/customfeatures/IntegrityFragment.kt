/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.app.AlertDialog
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.json.JSONObject

/**
 * Management screen of the "Play Integrity Fix" feature; one screen for the
 * whole stack, opened by tapping the title of the switch on the main screen:
 *
 *  - keybox   state, automatic renewal, renew now, delete
 *  - teesim   state and the per-app target list
 *  - pif      pif.json state, refresh, clear
 *
 * The main switch only arms the features (a restart applies them); everything
 * on this screen acts live through the system_server keybox service.
 */
class IntegrityFragment : SettingsBasePreferenceFragment() {

    private companion object {
        /**
         * The UI must never stay on "renewing"/"fetching" forever: when the
         * worker has not answered by then (a hanging DNS lookup, a stuck
         * socket), the row is reset and the failure is shown.
         */
        const val WATCHDOG_MS = 120_000L
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.integrity_fix, rootKey)
        bindKeybox()
        bindTeesim()
        bindPif()
        bindCooldowns()
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(R.string.integrity_fix_title)
        refresh()
    }

    private fun refresh() {
        refreshKeybox()
        refreshTeesim()
        refreshPif()
    }

    //
    // Keybox
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

    /** The two automatic check cooldowns, both thirty minutes by default. */
    private fun bindCooldowns() {
        val period = findPreference<ListPreference>("check_period") ?: return
        val unlock = findPreference<ListPreference>("unlock_cooldown") ?: return
        period.value = CheckCooldown.periodMinutes(requireContext()).toString()
        period.setOnPreferenceChangeListener { _, value ->
            CheckCooldown.setPeriodMinutes(requireContext(), (value as String).toInt())
            true
        }
        unlock.value = CheckCooldown.unlockMinutes(requireContext()).toString()
        unlock.setOnPreferenceChangeListener { _, value ->
            CheckCooldown.setUnlockMinutes(requireContext(), (value as String).toInt())
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
        auto.isChecked = Integrity.enabled(Integrity.KEYBOX)
        auto.setOnPreferenceChangeListener { _, value ->
            val enabled = value as Boolean
            Integrity.set(Integrity.KEYBOX, enabled)
            if (enabled) IntegrityJobService.schedule(requireContext())
            else IntegrityJobService.cancel(requireContext())
            Integrity.askReboot(
                this,
                if (enabled) {
                    R.string.integrity_reboot_message
                } else {
                    R.string.integrity_reboot_off_message
                },
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
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val watchdog = Runnable {
            if (!done.compareAndSet(false, true) || !isAdded) return@Runnable
            renew.isEnabled = true
            renew.summary = getString(R.string.keybox_renew_summary)
            Toast.makeText(requireContext(), R.string.keybox_renew_failed, Toast.LENGTH_LONG).show()
            refresh()
        }
        handler.postDelayed(watchdog, WATCHDOG_MS)
        Thread {
            val result = try {
                KeyboxRenewal.renew(force = true, autoRotate = autoRotate)
            } catch (t: Throwable) {
                KeyboxRenewal.Result.Failed(t.message ?: t.javaClass.simpleName)
            }
            activity?.runOnUiThread {
                if (!done.compareAndSet(false, true)) return@runOnUiThread
                handler.removeCallbacks(watchdog)
                if (!isAdded) return@runOnUiThread
                renew.isEnabled = true
                renew.summary = getString(R.string.keybox_renew_summary)
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
    // TEE Simulator
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
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val watchdog = Runnable {
            if (!done.compareAndSet(false, true) || !isAdded) return@Runnable
            refresh.isEnabled = true
            refresh.summary = getString(R.string.pif_refresh_summary)
            Toast.makeText(requireContext(), R.string.pif_refresh_failed, Toast.LENGTH_LONG).show()
            refresh()
        }
        handler.postDelayed(watchdog, WATCHDOG_MS)
        Thread {
            val json = try {
                KeyboxRenewal.fetchPif()
            } catch (t: Throwable) {
                null
            }
            val installed = json != null && IntegrityServiceClient.putPif(json)
            activity?.runOnUiThread {
                if (!done.compareAndSet(false, true)) return@runOnUiThread
                handler.removeCallbacks(watchdog)
                if (!isAdded) return@runOnUiThread
                refresh.isEnabled = true
                refresh.summary = getString(R.string.pif_refresh_summary)
                Toast.makeText(
                    requireContext(),
                    if (installed) R.string.pif_refresh_ok else R.string.pif_refresh_failed,
                    Toast.LENGTH_LONG,
                ).show()
                refresh()
            }
        }.start()
    }
}
