package hackuna.matata.ml.diarization

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeechRegion
import hackuna.matata.core.model.StenoException
import hackuna.matata.core.model.VoiceActivityDetector
import hackuna.matata.ml.runtime.ModelManager
import hackuna.matata.ml.sherpa.SherpaRuntime

object DiarizationModelIds {
    const val VAD = "vad"
    const val SEGMENTATION = "diarization.segmentation"
    const val EMBEDDING = "diarization.embedding"
}

class SileroVoiceActivityDetector(
    models: ModelManager,
    config: PipelineConfig,
) : VoiceActivityDetector {

    private val vad: Vad

    init {
        if (!SherpaRuntime.isAvailable()) {
            throw StenoException(ErrorCode.MODELS_MISSING, "Нет libsherpa-onnx-jni.so (scripts/fetch_sherpa.sh)")
        }
        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = models.require(DiarizationModelIds.VAD),
                    threshold = config.vadThreshold,
                    minSilenceDuration = MIN_SILENCE_SEC,
                    minSpeechDuration = MIN_SPEECH_SEC,
                    windowSize = WINDOW,
                    maxSpeechDuration = MAX_SPEECH_SEC,
                ),
                sampleRate = PcmAudio.MODEL_SAMPLE_RATE,
                numThreads = 1,
            ),
        )
    }

    override fun detect(audio: PcmAudio): List<SpeechRegion> {
        require(audio.sampleRate == PcmAudio.MODEL_SAMPLE_RATE) { "VAD ждёт 16 кГц, пришло ${audio.sampleRate}" }
        vad.reset()
        val regions = ArrayList<SpeechRegion>()

        fun drain() {
            while (!vad.empty()) {
                val segment = vad.front()
                val startMs = samplesToMs(segment.start)
                val endMs = samplesToMs(segment.start + segment.samples.size)
                regions += SpeechRegion(startMs, endMs)
                vad.pop()
            }
        }

        val samples = audio.samples
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + WINDOW, samples.size)
            vad.acceptWaveform(samples.copyOfRange(offset, end))
            drain()
            offset = end
        }
        vad.flush()
        drain()
        return regions
    }

    override fun close() = vad.release()

    private companion object {
        const val WINDOW = 512
        const val MIN_SILENCE_SEC = 0.25f
        const val MIN_SPEECH_SEC = 0.25f
        const val MAX_SPEECH_SEC = 30f

        fun samplesToMs(samples: Long): Long = samples * 1000 / PcmAudio.MODEL_SAMPLE_RATE
    }
}
