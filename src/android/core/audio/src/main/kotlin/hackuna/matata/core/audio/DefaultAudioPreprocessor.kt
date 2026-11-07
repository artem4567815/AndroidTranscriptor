package hackuna.matata.core.audio

import hackuna.matata.core.model.AudioPreprocessor
import hackuna.matata.core.model.PcmAudio
import kotlin.math.abs

/**
 * Предобработка перед VAD и ASR: удаление постоянной составляющей и нормализация пика.
 * Шумоподавление — TODO(роль 2): OfflineSpeechDenoiser из sherpa-onnx по флагу enableDenoise.
 */
class DefaultAudioPreprocessor(
    private val targetPeak: Float = 0.9f,
    private val maxGain: Float = 10f,
) : AudioPreprocessor {

    override fun process(audio: PcmAudio): PcmAudio {
        val s = audio.samples
        if (s.isEmpty()) return audio
        // DC-фильтр первого порядка: y[n] = x[n] - x[n-1] + R * y[n-1]
        val out = FloatArray(s.size)
        val r = 0.995f
        var prevX = 0f
        var prevY = 0f
        var peak = 0f
        for (i in s.indices) {
            val y = s[i] - prevX + r * prevY
            prevX = s[i]
            prevY = y
            out[i] = y
            peak = maxOf(peak, abs(y))
        }
        if (peak > 1e-6f) {
            val gain = (targetPeak / peak).coerceAtMost(maxGain)
            for (i in out.indices) out[i] = (out[i] * gain).coerceIn(-1f, 1f)
        }
        return PcmAudio(out, audio.sampleRate)
    }
}
