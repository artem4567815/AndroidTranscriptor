package hackuna.matata

import hackuna.matata.core.model.RecognizedSegment
import hackuna.matata.core.model.SpeechRecognizer
import java.util.Locale

/**
 * ТОЛЬКО DEBUG. Заглушка распознавания, чтобы проверять VAD и диаризацию на телефоне,
 * пока настоящий ASR не готов. Вместо текста — длительность куска, например «[речь 3,2 с]».
 * Включается флагом -Psteno.fakeAsr=true. В release класса нет.
 */
class FakeSpeechRecognizer : SpeechRecognizer {
    override fun recognize(samples: FloatArray, sampleRate: Int): RecognizedSegment {
        val ms = samples.size * 1000L / sampleRate
        val text = String.format(Locale.forLanguageTag("ru"), "[речь %.1f с]", ms / 1000.0)
        return RecognizedSegment(startMs = 0, endMs = ms, text = text)
    }

    override fun close() = Unit
}
