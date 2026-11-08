package hackuna.matata

import android.app.Application
import hackuna.matata.core.audio.audioModule
import hackuna.matata.core.data.TranscriptRepository
import hackuna.matata.core.data.dataModule
import hackuna.matata.di.APP_SCOPE
import hackuna.matata.di.appModule
import hackuna.matata.ml.asr.asrModule
import hackuna.matata.ml.diarization.diarizationModule
import hackuna.matata.ml.llm.llmModule
import hackuna.matata.ml.runtime.runtimeModule
import hackuna.matata.pipeline.pipelineModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named

class StenoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@StenoApp)
            modules(
                audioModule, dataModule, runtimeModule, asrModule, diarizationModule, llmModule, pipelineModule,
                appModule,
            )
        }
        get<CoroutineScope>(named(APP_SCOPE)).launch(Dispatchers.IO) {
            get<TranscriptRepository>().resetStaleProcessing()
        }
    }
}
