/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat

/**
 * Switch row whose title opens the feature screen. The switch itself and the
 * rest of the row keep the normal SwitchPreferenceCompat behaviour, only a tap
 * on the title text navigates (the same place where the other rows are tapped).
 */
class TitleClickSwitchPreference(context: Context, attrs: AttributeSet) :
    SwitchPreferenceCompat(context, attrs) {

    var onTitleClick: (() -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val title = holder.itemView.findViewById<View>(android.R.id.title) ?: return
        title.isClickable = true
        title.setOnClickListener { onTitleClick?.invoke() }
    }
}
