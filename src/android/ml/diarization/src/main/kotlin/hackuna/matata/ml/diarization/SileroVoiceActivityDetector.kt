package hackuna.matata.ml.diarization

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

/**
 * VAD на Silero через sherpa-onnx (com.k2fsa.sherpa.onnx.Vad).
 *
 * TODO(роль 3): VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model, threshold = config.vadThreshold,
 *  minSilenceDuration, minSpeechDuration, windowSize = 512), sampleRate = 16000, numThreads = 1);
 *  подавать окна по 512 отсчётов через acceptWaveform, забирать сегменты (front/pop), в конце flush().
 *  Сегменты VAD нужны пайплайну, чтобы резать длинные реплики по паузам (maxSegmentMs).
 */
class SileroVoiceActivityDetector(
    private val models: ModelManager,
    private val config: PipelineConfig,
) : VoiceActivityDetector {
    init {
        if (!SherpaRuntime.isAvailable()) throw StenoException(ErrorCode.MODELS_MISSING, "Нет libsherpa-onnx-jni.so")
        models.require(DiarizationModelIds.VAD)
    }

    override fun detect(audio: PcmAudio): List<SpeechRegion> {
        throw NotImplementedError("TODO(роль 3): Silero VAD через sherpa-onnx (threshold=${config.vadThreshold})")
    }

    override fun close() {
        // TODO(роль 3): vad.release()
    }
}
