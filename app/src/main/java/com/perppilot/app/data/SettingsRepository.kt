package com.perppilot.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(load())

    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    private fun load(): AppSettings = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull()
    } ?: AppSettings()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        prefs.edit { putString(KEY, json.encodeToString(AppSettings.serializer(), next)) }
        state.value = next
    }

    private companion object {
        const val KEY = "app_settings_json"
    }
}
