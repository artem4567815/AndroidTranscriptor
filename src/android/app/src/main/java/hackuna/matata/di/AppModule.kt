package hackuna.matata.di

import hackuna.matata.BuildTypeConfig
import hackuna.matata.core.model.TranscriptionPipeline
import hackuna.matata.ml.runtime.ModelManager
import hackuna.matata.pipeline.REAL_PIPELINE
import hackuna.matata.processing.PipelineProvider
import hackuna.matata.processing.TranscriptionController
import hackuna.matata.storage.RecordingFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

const val APP_SCOPE = "appScope"

val appModule = module {
    single(named(APP_SCOPE)) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { RecordingFiles(androidContext(), get(), get()) }
    single {
        val models = get<ModelManager>()
        val koin = getKoin()
        // В debug может работать FakePipeline (его класс есть только в src/debug), в release — никогда.
        val provider = PipelineProvider {
            BuildTypeConfig.fakePipeline(koin, models.isReady())
                ?: koin.get<TranscriptionPipeline>(named(REAL_PIPELINE))
        }
        TranscriptionController(androidContext(), get(), provider, get(named(APP_SCOPE)))
    }
}
