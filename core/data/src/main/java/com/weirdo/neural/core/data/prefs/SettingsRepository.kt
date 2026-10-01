package com.weirdo.neural.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("weirdo_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME_DARK = booleanPreferencesKey("theme_dark")
        val AUTO_EXECUTE = booleanPreferencesKey("auto_execute_tools")
        val ACTIVE_MODEL = stringPreferencesKey("active_model_id")
    }

    val darkTheme: Flow<Boolean> = context.dataStore.data.map { it[Keys.THEME_DARK] ?: true }
    val autoExecuteTools: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_EXECUTE] ?: false }
    val activeModelId: Flow<String?> = context.dataStore.data.map { it[Keys.ACTIVE_MODEL] }

    suspend fun setDarkTheme(v: Boolean) = context.dataStore.edit { it[Keys.THEME_DARK] = v }
    suspend fun setAutoExecute(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_EXECUTE] = v }
    suspend fun setActiveModel(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.ACTIVE_MODEL) else it[Keys.ACTIVE_MODEL] = id
    }
}
