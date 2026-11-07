package hackuna.matata

import hackuna.matata.core.model.SpeechRecognizerFactory
import hackuna.matata.core.model.TranscriptionPipeline
import org.koin.core.Koin

/** Debug: FakePipeline прогоняет мост на телефоне целиком, пока движки не готовы. */
object BuildTypeConfig {
    const val ALLOW_MISSING_MODELS = true
    const val WRITE_TIMINGS = true

    /** Режим из -Psteno.fakePipeline: auto (по умолчанию) — когда моделей нет; true — всегда; false — никогда. */
    fun fakePipeline(koin: Koin, modelsReady: Boolean): TranscriptionPipeline? = when (BuildConfig.FAKE_PIPELINE) {
        "true" -> FakePipeline(koin.get())
        "false" -> null
        else -> if (modelsReady) null else FakePipeline(koin.get())
    }

    /** -Psteno.fakeAsr=true: настоящие VAD и диаризация, но вместо распознавания — длительность куска. */
    fun fakeAsrFactory(): SpeechRecognizerFactory? =
        if (BuildConfig.FAKE_ASR == "true") SpeechRecognizerFactory { FakeSpeechRecognizer() } else null
}
