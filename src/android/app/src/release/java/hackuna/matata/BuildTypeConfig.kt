package hackuna.matata

import hackuna.matata.core.model.SpeechRecognizerFactory
import hackuna.matata.core.model.TranscriptionPipeline
import org.koin.core.Koin

/**
 * Release: никаких заглушек. Без моделей — честная ошибка MODELS_MISSING;
 * класса FakePipeline в release-сборке физически нет (он в src/debug).
 */
object BuildTypeConfig {
    const val ALLOW_MISSING_MODELS = false
    const val WRITE_TIMINGS = false

    @Suppress("UNUSED_PARAMETER")
    fun fakePipeline(koin: Koin, modelsReady: Boolean): TranscriptionPipeline? = null

    fun fakeAsrFactory(): SpeechRecognizerFactory? = null
}
