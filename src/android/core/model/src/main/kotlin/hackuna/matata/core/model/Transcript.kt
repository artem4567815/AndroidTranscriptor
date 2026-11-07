package hackuna.matata.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Реплика строгого формата экспорта организаторов.
 * Имена полей JSON менять нельзя ни при каких условиях.
 */
@Serializable
data class Utterance(
    @SerialName("speaker_id") val speakerId: String, // "speaker_1", "speaker_2", ...
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
)

/** Корень строгого JSON: `{"transcript": [...]}`. */
@Serializable
data class Transcript(val transcript: List<Utterance>)
