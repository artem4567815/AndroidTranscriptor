package hackuna.matata.core.data

import androidx.room.Room
import hackuna.matata.core.data.db.StenoDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single {
        Room.databaseBuilder(androidContext(), StenoDatabase::class.java, StenoDatabase.NAME).build()
    }
    single { get<StenoDatabase>().dao() }
    single<TranscriptRepository> { RoomTranscriptRepository(get()) }
    single { PipelineSettingsRepository(androidContext()) }
}
