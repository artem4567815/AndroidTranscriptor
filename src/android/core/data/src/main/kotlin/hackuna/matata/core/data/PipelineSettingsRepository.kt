package hackuna.matata.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import hackuna.matata.core.model.PipelineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.settingsStore by preferencesDataStore(name = "pipeline_settings")

/** Последний использованный PipelineConfig (DataStore Preferences). */
class PipelineSettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("pipeline_config")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val config: Flow<PipelineConfig> = context.settingsStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString(PipelineConfig.serializer(), it) }.getOrNull() }
            ?: PipelineConfig()
    }

    suspend fun current(): PipelineConfig = config.first()

    suspend fun save(config: PipelineConfig) {
        context.settingsStore.edit { it[key] = json.encodeToString(PipelineConfig.serializer(), config) }
    }
}
