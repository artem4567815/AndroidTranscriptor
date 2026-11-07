package hackuna.matata.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RecordingEntity::class, UtteranceEntity::class, ResultEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class StenoDatabase : RoomDatabase() {
    abstract fun dao(): StenoDao

    companion object {
        const val NAME = "steno.db"
    }
}
