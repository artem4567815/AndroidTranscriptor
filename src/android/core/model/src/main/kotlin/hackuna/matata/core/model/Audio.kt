package hackuna.matata.core.model

/** PCM моно float в диапазоне [-1, 1]. По умолчанию 16 кГц — формат всех моделей. */
class PcmAudio(val samples: FloatArray, val sampleRate: Int = 16_000) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate

    /** Отрезок [startMs, endMs) с обрезкой по границам записи. */
    fun slice(startMs: Long, endMs: Long): FloatArray {
        val from = msToSample(startMs).coerceIn(0, samples.size)
        val to = msToSample(endMs).coerceIn(from, samples.size)
        return samples.copyOfRange(from, to)
    }

    fun msToSample(ms: Long): Int = (ms * sampleRate / 1000L).toInt()

    companion object {
        const val MODEL_SAMPLE_RATE = 16_000
    }
}

/** Участок речи, найденный VAD. */
data class SpeechRegion(val startMs: Long, val endMs: Long)

/** Сегмент диаризации: номер спикера (с нуля, как отдаёт кластеризация) и границы. */
data class SpeakerSegment(val speaker: Int, val startMs: Long, val endMs: Long)

data class TimedToken(val text: String, val startMs: Long)

/** Результат ASR для одного сегмента. Все метки отсчитываются от начала переданного ASR аудио. */
data class RecognizedSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val tokens: List<TimedToken> = emptyList(),
)

/** Сегмент ASR с привязкой к спикеру — вход склейки реплик. */
data class SpeakerText(val speaker: Int, val startMs: Long, val endMs: Long, val text: String)
