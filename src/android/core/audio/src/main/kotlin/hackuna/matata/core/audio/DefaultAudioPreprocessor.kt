package hackuna.matata.core.audio

import hackuna.matata.core.model.AudioPreprocessor
import hackuna.matata.core.model.PcmAudio
import kotlin.math.abs

/**
 * Предобработка перед VAD и ASR: удаление постоянной составляющей и нормализация пика.
 */
class DefaultAudioPreprocessor(
    private val targetPeak: Float = 0.9f,
    private val maxGain: Float = 10f,
) : AudioPreprocessor {

    override fun process(audio: PcmAudio): PcmAudio {
        val s = audio.samples
        if (s.isEmpty()) return audio
        val r = 0.995f
        var prevX = 0f
        var prevY = 0f
        var peak = 0f
        for (i in s.indices) {
            val y = s[i] - prevX + r * prevY
            prevX = s[i]
            prevY = y
            s[i] = y
            peak = maxOf(peak, abs(y))
        }
        if (peak > 1e-6f) {
            val gain = (targetPeak / peak).coerceAtMost(maxGain)
            for (i in s.indices) s[i] = (s[i] * gain).coerceIn(-1f, 1f)
        }
        return audio
    }
}
