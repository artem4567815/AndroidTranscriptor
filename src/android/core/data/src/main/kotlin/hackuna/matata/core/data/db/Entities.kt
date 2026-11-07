package hackuna.matata.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourcePath: String,
    val audioPath: String,
    val durationMs: Long,
    val createdAt: Long,
    val status: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/** Реплика. variant: 0 — сырой ASR (экспорт), 1 — правка LLM. */
@Entity(
    tableName = "utterances",
    primaryKeys = ["recordingId", "variant", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class UtteranceEntity(
    val recordingId: String,
    val variant: Int,
    val idx: Int,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    companion object {
        const val RAW = 0
        const val LLM = 1
    }
}

@Entity(
    tableName = "results",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ResultEntity(
    @PrimaryKey val recordingId: String,
    val protocol: String?,
    val speakerNamesJson: String,
    val timingsJson: String,
    val audioDurationMs: Long,
    val hasLlmCorrection: Boolean,
)
