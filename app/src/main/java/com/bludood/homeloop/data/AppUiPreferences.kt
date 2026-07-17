package com.bludood.homeloop.data

import android.content.Context
import androidx.core.content.edit

enum class AppViewMode(val label: String) {
    BASIC("Basic"),
    ADVANCED("Advanced"),
}

enum class AdvancedSection(val preferenceKey: String) {
    RENDERING_OPTIONS("rendering_options"),
    OPTIMIZATION("optimization"),
    INPUT_DIAGNOSTICS("input_diagnostics"),
    RUNTIME_DIAGNOSTICS("runtime_diagnostics"),
    VIRTUAL_TIMELINE("virtual_timeline"),
}

class AppUiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun viewMode(): AppViewMode = preferences.getString(KEY_VIEW_MODE, null)
        ?.let { stored -> AppViewMode.entries.firstOrNull { it.name == stored } }
        ?: AppViewMode.BASIC

    fun setViewMode(mode: AppViewMode) {
        preferences.edit { putString(KEY_VIEW_MODE, mode.name) }
    }

    fun isSectionExpanded(section: AdvancedSection): Boolean = preferences.getBoolean(
        "$SECTION_EXPANDED_PREFIX${section.preferenceKey}",
        true,
    )

    fun setSectionExpanded(section: AdvancedSection, expanded: Boolean) {
        preferences.edit {
            putBoolean("$SECTION_EXPANDED_PREFIX${section.preferenceKey}", expanded)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "video_widget_ui"
        const val KEY_VIEW_MODE = "view_mode"
        const val SECTION_EXPANDED_PREFIX = "section_expanded_"
    }
}
