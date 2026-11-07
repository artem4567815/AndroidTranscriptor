package hackuna.matata.core.data

import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.Stage
import hackuna.matata.core.model.rtfOf
import hackuna.matata.core.model.Transcript

enum class RecordingStatus { NEW, PROCESSING, DONE, ERROR }

data class RecordingInfo(
    val id: String,
    val title: String,
    /** Исходный файл (копия во внутренней памяти) — его читает декодер. */
    val sourcePath: String,
    /** Файл, который умеет играть WebView (для редких форматов — WAV-копия). */
    val audioPath: String,
    val durationMs: Long,
    val createdAt: Long,
    val status: RecordingStatus,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
)

/** Сохранённый результат обработки. [transcript] — сырой ASR (с ручными правками), основа экспорта. */
data class StoredResult(
    val recordingId: String,
    val transcript: Transcript,
    val llmCorrected: Transcript?,
    val protocol: String?,
    val speakerNames: Map<String, String>,
    val timings: Map<Stage, Long>,
    val audioDurationMs: Long,
) {
    val rtf: Double get() = rtfOf(timings, audioDurationMs)
}
