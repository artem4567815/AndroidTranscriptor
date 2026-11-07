package hackuna.matata.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StenoDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    suspend fun listRecordings(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecording(r: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecording(id: String)

    @Query("UPDATE recordings SET status = :status, errorCode = :errorCode, errorMessage = :errorMessage WHERE id = :id")
    suspend fun setStatus(id: String, status: String, errorCode: String?, errorMessage: String?)

    @Query("UPDATE recordings SET status = :to WHERE status = :from")
    suspend fun replaceStatus(from: String, to: String): Int

    @Query("SELECT * FROM utterances WHERE recordingId = :id AND variant = :variant ORDER BY idx")
    suspend fun utterances(id: String, variant: Int): List<UtteranceEntity>

    @Query("SELECT * FROM utterances WHERE recordingId = :id AND variant = :variant ORDER BY idx")
    fun observeUtterances(id: String, variant: Int): Flow<List<UtteranceEntity>>

    @Query("DELETE FROM utterances WHERE recordingId = :id")
    suspend fun deleteUtterances(id: String)

    @Insert
    suspend fun insertUtterances(items: List<UtteranceEntity>)

    @Upsert
    suspend fun upsertUtterance(u: UtteranceEntity)

    @Query("SELECT * FROM results WHERE recordingId = :id")
    suspend fun getResult(id: String): ResultEntity?

    @Upsert
    suspend fun upsertResult(r: ResultEntity)

    @Transaction
    suspend fun replaceResult(result: ResultEntity, utterances: List<UtteranceEntity>, status: String) {
        deleteUtterances(result.recordingId)
        insertUtterances(utterances)
        upsertResult(result)
        setStatus(result.recordingId, status, null, null)
    }
}
