package hackuna.matata.ml.asr

import hackuna.matata.core.model.SpeechRecognizerFactory
import org.koin.dsl.module

val asrModule = module {
    single<SpeechRecognizerFactory> { SpeechRecognizerFactory { config -> GigaAmSpeechRecognizer(get(), config) } }
}
