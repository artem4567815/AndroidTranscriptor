package hackuna.matata.ml.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Описание `assets/models/manifest.json`. */
@Serializable
data class ModelManifest(
    val version: Int,
    val files: List<ModelFile>,
) {
    fun file(id: String): ModelFile? = files.find { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): ModelManifest = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class ModelFile(
    /** Ключ, по которому движки спрашивают путь: "asr.encoder", "vad", ... */
    val id: String,
    /** Путь относительно assets/models и filesDir/models. */
    val path: String,
    val sizeBytes: Long = 0,
    val sha256: String = "",
    val source: String = "",
    val license: String = "",
    val purpose: String = "",
    val version: String = "",
    /** Без обязательных файлов обработка невозможна (MODELS_MISSING). LLM — опциональна. */
    val required: Boolean = true,
)

/** Состояние модели для SettingsPage. */
data class ModelStatus(val file: ModelFile, val present: Boolean)
