package hackuna.matata.ml.diarization

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import hackuna.matata.core.model.Diarizer
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeakerSegment
import hackuna.matata.core.model.SpeechRegion
import hackuna.matata.core.model.StenoException
import hackuna.matata.ml.runtime.ModelManager
import hackuna.matata.ml.sherpa.SherpaRuntime
import kotlin.math.roundToLong

class SherpaDiarizer(
    models: ModelManager,
    config: PipelineConfig,
) : Diarizer {

    private val sd: OfflineSpeakerDiarization
    private val embeddingModel: String = models.require(DiarizationModelIds.EMBEDDING)

    /** Модель отпечатков для второго прохода — создаётся, только если он нужен. */
    private var extractor: SpeakerEmbeddingExtractor? = null

    init {
        if (!SherpaRuntime.isAvailable()) {
            throw StenoException(ErrorCode.MODELS_MISSING, "Нет libsherpa-onnx-jni.so (scripts/fetch_sherpa.sh)")
        }
        sd = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = models.require(DiarizationModelIds.SEGMENTATION),
                        // Главный рычаг скорости: число окон и отпечатков ~ 1 / windowShiftRatio.
                        windowShiftRatio = config.diarizationWindowShift,
                    ),
                    numThreads = config.numThreads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = embeddingModel,
                    numThreads = config.numThreads,
                ),
                clustering = DiarizationMapping.clustering(config),
                minDurationOn = MIN_DURATION_ON_SEC,
                minDurationOff = MIN_DURATION_OFF_SEC,
            ),
        )
        check(sd.sampleRate() == PcmAudio.MODEL_SAMPLE_RATE) {
            "Модель диаризации ждёт ${sd.sampleRate()} Гц, а пайплайн отдаёт ${PcmAudio.MODEL_SAMPLE_RATE}"
        }
    }

    override fun diarize(audio: PcmAudio, config: PipelineConfig, onProgress: (Float) -> Unit): List<SpeakerSegment> {
        require(audio.sampleRate == PcmAudio.MODEL_SAMPLE_RATE) { "Диаризация ждёт 16 кГц, пришло ${audio.sampleRate}" }
        if (audio.samples.isEmpty()) return emptyList()

        sd.setConfig(sd.config.copy(clustering = DiarizationMapping.clustering(config)))

        val raw = sd.processWithCallback(audio.samples, callback = ProgressCallback(onProgress))
        onProgress(1f)
        return DiarizationMapping.toSpeakerSegments(raw)
    }

    /**
     * Второй проход: отпечаток голоса для каждого участка речи (с запасом [PipelineConfig.embeddingPaddingMs]
     * только в тишину) и группировка [SpeakerClustering]. Точнее sherpa на коротких репликах.
     */
    override fun labelUnits(audio: PcmAudio, units: List<SpeechRegion>, config: PipelineConfig): List<Int> {
        if (units.isEmpty()) return emptyList()
        val ext = extractor ?: SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(model = embeddingModel, numThreads = config.numThreads),
        ).also { extractor = it }
        val pad = config.embeddingPaddingMs
        val embeddings = units.mapIndexed { i, u ->
            val from = maxOf(u.startMs - pad, units.getOrNull(i - 1)?.endMs ?: 0L).coerceAtMost(u.startMs)
            val to = minOf(u.endMs + pad, units.getOrNull(i + 1)?.startMs ?: audio.durationMs).coerceAtLeast(u.endMs)
            val stream = ext.createStream()
            try {
                stream.acceptWaveform(audio.slice(from, to), audio.sampleRate)
                stream.inputFinished()
                if (ext.isReady(stream)) ext.compute(stream) else null // слишком короткий кусок
            } finally {
                stream.release()
            }
        }
        return SpeakerClustering.cluster(embeddings, config.numSpeakers, config.minSpeakerSilhouette).toList()
    }

    override fun close() {
        sd.release()
        extractor?.release()
        extractor = null
    }

    private companion object {
        const val MIN_DURATION_ON_SEC = 0.2f

        const val MIN_DURATION_OFF_SEC = 0.5f
    }
}

internal class ProgressCallback(private val onProgress: (Float) -> Unit) : (Int, Int, Long) -> Int {
    override fun invoke(processed: Int, total: Int, arg: Long): Int {
        if (total > 0) onProgress(processed.toFloat() / total)
        return 0
    }
}

internal object DiarizationMapping {

    fun clustering(config: PipelineConfig) = FastClusteringConfig(
        numClusters = if (config.numSpeakers > 0) config.numSpeakers else -1,
        threshold = config.clusterThreshold,
    )

    fun toSpeakerSegments(raw: Array<OfflineSpeakerDiarizationSegment>): List<SpeakerSegment> =
        raw.map {
            SpeakerSegment(
                speaker = it.speaker,
                startMs = (it.start * 1000).roundToLong().coerceAtLeast(0),
                endMs = (it.end * 1000).roundToLong(),
            )
        }
            .filter { it.endMs > it.startMs }
            .sortedWith(compareBy<SpeakerSegment> { it.startMs }.thenBy { it.endMs })
}
