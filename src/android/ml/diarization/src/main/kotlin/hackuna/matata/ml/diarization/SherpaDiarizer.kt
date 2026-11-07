package hackuna.matata.ml.diarization

import hackuna.matata.core.model.Diarizer
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeakerSegment
import hackuna.matata.core.model.StenoException
import hackuna.matata.ml.runtime.ModelManager
import hackuna.matata.ml.sherpa.SherpaRuntime

/**
 * Диаризация: pyannote-segmentation-3.0 + эмбеддинги 3D-Speaker (CAM++ / ERes2NetV2)
 * через sherpa-onnx OfflineSpeakerDiarization.
 *
 * TODO(роль 3):
 *  - OfflineSpeakerDiarizationConfig(segmentation = OfflineSpeakerSegmentationModelConfig(
 *      pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segmentationPath), numThreads),
 *      embedding = SpeakerEmbeddingExtractorConfig(embeddingPath, numThreads),
 *      clustering = FastClusteringConfig(numClusters = numSpeakers > 0 ? numSpeakers : -1,
 *                                        threshold = config.clusterThreshold))
 *  - processWithCallback(samples) { done, total -> onProgress(done / total) }
 *  - вернуть SpeakerSegment(speaker, startMs, endMs) (start/end у sherpa — в секундах)
 *  - подобрать порог кластеризации на стенде src/eval (eval_der.py).
 */
class SherpaDiarizer(
    private val models: ModelManager,
    private val config: PipelineConfig,
) : Diarizer {
    init {
        if (!SherpaRuntime.isAvailable()) throw StenoException(ErrorCode.MODELS_MISSING, "Нет libsherpa-onnx-jni.so")
        models.require(DiarizationModelIds.SEGMENTATION)
        models.require(DiarizationModelIds.EMBEDDING)
    }

    override fun diarize(audio: PcmAudio, config: PipelineConfig, onProgress: (Float) -> Unit): List<SpeakerSegment> {
        throw NotImplementedError(
            "TODO(роль 3): sherpa-onnx OfflineSpeakerDiarization (speakers=${config.numSpeakers}, threshold=${config.clusterThreshold})",
        )
    }

    override fun close() {
        // TODO(роль 3): diarization.release()
    }
}
