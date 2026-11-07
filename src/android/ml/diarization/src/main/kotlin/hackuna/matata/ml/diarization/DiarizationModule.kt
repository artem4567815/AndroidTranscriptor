package hackuna.matata.ml.diarization

import hackuna.matata.core.model.DiarizerFactory
import hackuna.matata.core.model.VadFactory
import org.koin.dsl.module

val diarizationModule = module {
    single<VadFactory> { VadFactory { config -> SileroVoiceActivityDetector(get(), config) } }
    single<DiarizerFactory> { DiarizerFactory { config -> SherpaDiarizer(get(), config) } }
}
