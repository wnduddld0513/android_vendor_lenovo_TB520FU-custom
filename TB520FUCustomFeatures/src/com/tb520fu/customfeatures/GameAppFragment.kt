/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SeekBarPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/**
 * CPU and GPU level of one app in the game performance list, or remove it.
 * The custom level adds sliders for the single core, the multi core and the
 * GPU limits, saved per package.
 */
class GameAppFragment : SettingsBasePreferenceFragment() {

    private val pkg get() = requireArguments().getString(ARG_PKG)!!
    private val cpuPrefs = mutableListOf<SelectorWithWidgetPreference>()
    private val gpuPrefs = mutableListOf<SelectorWithWidgetPreference>()
    private lateinit var singlePref: SeekBarPreference
    private lateinit var multiPref: SeekBarPreference
    private lateinit var customGpuPref: SeekBarPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

        val cpuCategory = addLevels(R.string.game_cpu_category, "cpu",
            R.array.game_cpu_level_summaries, cpuPrefs) { levels, level ->
            levels.copy(cpu = level)
        }
        singlePref = addSlider(cpuCategory, "custom_single", R.string.game_custom_single_title, SLIDER_SINGLE)
        multiPref = addSlider(cpuCategory, "custom_multi", R.string.game_custom_multi_title, SLIDER_MULTI)

        val gpuCategory = addLevels(R.string.game_gpu_category, "gpu",
            R.array.game_gpu_level_summaries, gpuPrefs) { levels, level ->
            levels.copy(gpu = level)
        }
        customGpuPref = addSlider(gpuCategory, "custom_gpu", R.string.game_custom_gpu_title, SLIDER_GPU)

        val other = PreferenceCategory(ctx).apply { key = "other" }
        preferenceScreen.addPreference(other)
        other.addPreference(Preference(ctx).apply {
            key = "remove"
            title = getString(R.string.game_app_remove)
            setOnPreferenceClickListener {
                GameApps.remove(requireContext(), pkg)
                parentFragmentManager.popBackStack()
                true
            }
        })
    }

    private fun addLevels(
        titleRes: Int,
        prefix: String,
        summariesRes: Int,
        prefs: MutableList<SelectorWithWidgetPreference>,
        change: (GameApps.Levels, Int) -> GameApps.Levels,
    ): PreferenceCategory {
        val ctx = requireContext()
        val summaries = resources.getStringArray(summariesRes)
        val category = PreferenceCategory(ctx).apply {
            key = prefix
            title = getString(titleRes)
        }
        preferenceScreen.addPreference(category)
        GameApps.LEVELS.forEach { level ->
            val pref = SelectorWithWidgetPreference(ctx).apply {
                key = "${prefix}_$level"
                title = GameApps.levelName(ctx, level)
                summary = summaries[level]
                isPersistent = false
                setOnClickListener {
                    val levels = GameApps.get(requireContext(), pkg) ?: return@setOnClickListener
                    GameApps.set(requireContext(), pkg, change(levels, level))
                    updateChecked()
                }
            }
            category.addPreference(pref)
            prefs += pref
        }
        return category
    }

    /** A 30-100 % slider whose value is saved to this app's custom profile. */
    private fun addSlider(
        category: PreferenceCategory,
        key: String,
        titleRes: Int,
        slider: Int,
    ): SeekBarPreference {
        val pref = SeekBarPreference(requireContext()).apply {
            this.key = key
            title = getString(titleRes)
            summary = getString(R.string.game_custom_range_summary)
            min = GameApps.CUSTOM_MIN_PERCENT
            max = GameApps.CUSTOM_MAX_PERCENT
            seekBarIncrement = GameApps.CUSTOM_STEP_PERCENT
            showSeekBarValue = true
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val percent = value as Int
                if (!GameApps.isValidPercent(percent)) return@setOnPreferenceChangeListener false
                val custom = GameApps.custom(requireContext(), pkg)
                GameApps.setCustom(requireContext(), pkg, when (slider) {
                    SLIDER_SINGLE -> custom.copy(single = percent)
                    SLIDER_MULTI -> custom.copy(multi = percent)
                    else -> custom.copy(gpu = percent)
                })
                true
            }
        }
        category.addPreference(pref)
        return pref
    }

    override fun onResume() {
        super.onResume()
        if (GameApps.get(requireContext(), pkg) == null) {
            parentFragmentManager.popBackStack()
            return
        }
        activity?.title = GameApps.label(requireContext(), pkg)
        updateChecked()
    }

    private fun updateChecked() {
        val ctx = requireContext()
        val levels = GameApps.get(ctx, pkg) ?: return
        cpuPrefs.forEachIndexed { level, pref -> pref.isChecked = level == levels.cpu }
        gpuPrefs.forEachIndexed { level, pref -> pref.isChecked = level == levels.gpu }
        // Setting the value programmatically does not call the change listener.
        val custom = GameApps.custom(ctx, pkg)
        singlePref.value = custom.single
        multiPref.value = custom.multi
        customGpuPref.value = custom.gpu
        // The custom row shows the limits it will apply, so the category is
        // readable without opening the sliders.
        cpuPrefs[GameApps.LEVEL_CUSTOM].summary =
            getString(R.string.game_custom_cpu_summary, custom.single, custom.multi)
        gpuPrefs[GameApps.LEVEL_CUSTOM].summary =
            getString(R.string.game_custom_gpu_summary, custom.gpu)
        singlePref.isEnabled = levels.cpu == GameApps.LEVEL_CUSTOM
        multiPref.isEnabled = levels.cpu == GameApps.LEVEL_CUSTOM
        customGpuPref.isEnabled = levels.gpu == GameApps.LEVEL_CUSTOM
    }

    companion object {
        private const val ARG_PKG = "package"
        private const val SLIDER_SINGLE = 0
        private const val SLIDER_MULTI = 1
        private const val SLIDER_GPU = 2

        fun newInstance(pkg: String) = GameAppFragment().apply {
            arguments = Bundle().apply { putString(ARG_PKG, pkg) }
        }
    }
}
