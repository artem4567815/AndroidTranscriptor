package hackuna.matata.core.data

import hackuna.matata.core.data.db.RecordingEntity
import hackuna.matata.core.data.db.ResultEntity
import hackuna.matata.core.data.db.StenoDao
import hackuna.matata.core.data.db.UtteranceEntity
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.Stage
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptionResult
import hackuna.matata.core.model.Utterance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Хранилище записей, реплик и статуса обработки. Данные переживают перезапуск. */
interface TranscriptRepository {
    fun observeRecordings(): Flow<List<RecordingInfo>>
    suspend fun listRecordings(): List<RecordingInfo>
    suspend fun getRecording(id: String): RecordingInfo?
    suspend fun insertRecording(info: RecordingInfo)
    suspend fun deleteRecording(id: String)
    suspend fun setStatus(id: String, status: RecordingStatus, code: ErrorCode? = null, message: String? = null)

    /** После убийства процесса статус PROCESSING «застревает» — переводим такие записи в ERROR. */
    suspend fun resetStaleProcessing(): Int
    suspend fun saveResult(id: String, result: TranscriptionResult)
    suspend fun getResult(id: String): StoredResult?
    suspend fun updateUtterance(id: String, index: Int, text: String?, speakerId: String?)
    suspend fun renameSpeaker(id: String, speakerId: String, name: String)
}

class RoomTranscriptRepository(private val dao: StenoDao) : TranscriptRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val namesSerializer = MapSerializer(String.serializer(), String.serializer())
    private val timingsSerializer = MapSerializer(String.serializer(), Long.serializer())

    override fun observeRecordings(): Flow<List<RecordingInfo>> =
        dao.observeRecordings().map { list -> list.map { it.toInfo() } }

    override suspend fun listRecordings(): List<RecordingInfo> = dao.listRecordings().map { it.toInfo() }

    override suspend fun getRecording(id: String): RecordingInfo? = dao.getRecording(id)?.toInfo()

    override suspend fun insertRecording(info: RecordingInfo) = dao.insertRecording(info.toEntity())

    override suspend fun deleteRecording(id: String) = dao.deleteRecording(id)

    override suspend fun setStatus(id: String, status: RecordingStatus, code: ErrorCode?, message: String?) =
        dao.setStatus(id, status.name, code?.name, message)

    override suspend fun resetStaleProcessing(): Int =
        dao.replaceStatus(RecordingStatus.PROCESSING.name, RecordingStatus.ERROR.name)

    override suspend fun saveResult(id: String, result: TranscriptionResult) {
        val rows = result.transcript.transcript.toRows(id, UtteranceEntity.RAW) +
            (result.llmCorrected?.transcript?.toRows(id, UtteranceEntity.LLM) ?: emptyList())
        val entity = ResultEntity(
            recordingId = id,
            protocol = result.protocol,
            speakerNamesJson = json.encodeToString(namesSerializer, result.speakerNames),
            timingsJson = json.encodeToString(timingsSerializer, result.timings.mapKeys { it.key.name }),
            audioDurationMs = result.audioDurationMs,
            hasLlmCorrection = result.llmCorrected != null,
        )
        dao.replaceResult(entity, rows, RecordingStatus.DONE.name)
    }

    override suspend fun getResult(id: String): StoredResult? {
        val r = dao.getResult(id) ?: return null
        val raw = dao.utterances(id, UtteranceEntity.RAW).map { it.toUtterance() }
        val llm = if (r.hasLlmCorrection) dao.utterances(id, UtteranceEntity.LLM).map { it.toUtterance() } else null
        return StoredResult(
            recordingId = id,
            transcript = Transcript(raw),
            llmCorrected = llm?.let(::Transcript),
            protocol = r.protocol,
            speakerNames = json.decodeFromString(namesSerializer, r.speakerNamesJson),
            timings = json.decodeFromString(timingsSerializer, r.timingsJson)
                .mapNotNull { (k, v) -> Stage.entries.find { it.name == k }?.let { it to v } }.toMap(),
            audioDurationMs = r.audioDurationMs,
        )
    }

    override suspend fun updateUtterance(id: String, index: Int, text: String?, speakerId: String?) {
        val rows = dao.utterances(id, UtteranceEntity.RAW)
        val row = rows.getOrNull(index) ?: throw IllegalArgumentException("Нет реплики $index в записи $id")
        dao.upsertUtterance(row.copy(text = text ?: row.text, speakerId = speakerId ?: row.speakerId))
    }

    override suspend fun renameSpeaker(id: String, speakerId: String, name: String) {
        val r = dao.getResult(id) ?: throw IllegalArgumentException("Нет результата для $id")
        val names = json.decodeFromString(namesSerializer, r.speakerNamesJson).toMutableMap()
        if (name.isBlank()) names.remove(speakerId) else names[speakerId] = name.trim()
        dao.upsertResult(r.copy(speakerNamesJson = json.encodeToString(namesSerializer, names)))
    }
}

internal fun List<Utterance>.toRows(id: String, variant: Int) = mapIndexed { i, u ->
    UtteranceEntity(id, variant, i, u.speakerId, u.startMs, u.endMs, u.text)
}

internal fun UtteranceEntity.toUtterance() = Utterance(speakerId, startMs, endMs, text)

internal fun RecordingEntity.toInfo() = RecordingInfo(
    id = id,
    title = title,
    sourcePath = sourcePath,
    audioPath = audioPath,
    durationMs = durationMs,
    createdAt = createdAt,
    status = RecordingStatus.entries.find { it.name == status } ?: RecordingStatus.ERROR,
    errorCode = errorCode?.let { c -> ErrorCode.entries.find { it.name == c } },
    errorMessage = errorMessage,
)

internal fun RecordingInfo.toEntity() = RecordingEntity(
    id, title, sourcePath, audioPath, durationMs, createdAt, status.name, errorCode?.name, errorMessage,
)
