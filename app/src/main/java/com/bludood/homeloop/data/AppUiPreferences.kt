package com.bludood.homeloop.data

import android.content.Context
import androidx.core.content.edit

enum class ThemeChoice(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val dynamicColor: Boolean = false,
    val hapticFeedback: Boolean = true,
)

class AppUiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun settings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = preferences.getString(KEY_THEME, null)
                ?.let { stored -> ThemeChoice.entries.firstOrNull { it.name == stored } }
                ?: defaults.theme,
            dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, defaults.dynamicColor),
            hapticFeedback = preferences.getBoolean(KEY_HAPTIC_FEEDBACK, defaults.hapticFeedback),
        )
    }

    fun setSettings(settings: AppSettings) {
        preferences.edit {
            putString(KEY_THEME, settings.theme.name)
            putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            putBoolean(KEY_HAPTIC_FEEDBACK, settings.hapticFeedback)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "video_widget_ui"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    }
}
