package hackuna.matata.core.model

import kotlinx.serialization.json.Json

/** Единственный источник строгого JSON. Используется экспортом и мостом. */
object TranscriptJson {
    val strict: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    val pretty: Json = Json(strict) { prettyPrint = true }
    val lenient: Json = Json { ignoreUnknownKeys = true }

    fun encode(t: Transcript, pretty: Boolean = true): String =
        (if (pretty) this.pretty else strict).encodeToString(Transcript.serializer(), t)

    fun decode(json: String): Transcript = strict.decodeFromString(Transcript.serializer(), json)
}
