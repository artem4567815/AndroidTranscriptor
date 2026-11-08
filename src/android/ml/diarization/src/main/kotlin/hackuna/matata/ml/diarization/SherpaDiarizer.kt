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

        val windows = DiarizationMapping.windows(audio.durationMs)
        if (windows.size == 1) {
            val raw = sd.processWithCallback(audio.samples, callback = ProgressCallback(onProgress))
            onProgress(1f)
            return DiarizationMapping.toSpeakerSegments(raw)
        }
        val out = ArrayList<SpeakerSegment>()
        windows.forEachIndexed { i, w ->
            val chunk = audio.slice(w.startMs, w.endMs)
            val raw = sd.processWithCallback(chunk, callback = ProgressCallback { f -> onProgress((i + f) / windows.size) })
            out += DiarizationMapping.stitch(DiarizationMapping.toSpeakerSegments(raw), w, i)
        }
        onProgress(1f)
        return out.sortedWith(compareBy<SpeakerSegment> { it.startMs }.thenBy { it.endMs })
    }

    /**
     * Второй проход: отпечаток голоса для каждого участка речи (с запасом [PipelineConfig.embeddingPaddingMs]
     * только в тишину) и группировка [SpeakerClustering]. Точнее sherpa на коротких репликах.
     *
     * Короткий участок (< [DiarizationMapping.SHORT_UNIT_MS]) — среднее нескольких отпечатков со сдвигом краёв
     * на 100 мс: один отпечаток по 0,5–1 с речи «дрожит», и реплика переходит от спикера к спикеру от громкости
     * на 1 дБ (игра в слова: DER 7,6% ↔ 23%, с усреднением — 2,5% в обоих случаях, docs/TESTS.md).
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
            if (u.endMs - u.startMs < DiarizationMapping.SHORT_UNIT_MS) {
                val crops = DiarizationMapping.shortUnitCrops(u, from, to, audio.durationMs)
                DiarizationMapping.average(crops.mapNotNull { (a, b) -> embed(ext, audio, a, b) })
                    ?: embed(ext, audio, from, to)
            } else {
                embed(ext, audio, from, to)
            }
        }
        return SpeakerClustering.cluster(embeddings, config.numSpeakers, config.minSpeakerSilhouette).toList()
    }

    /** Отпечаток голоса куска [fromMs, toMs) или null, если кусок слишком короткий. */
    private fun embed(ext: SpeakerEmbeddingExtractor, audio: PcmAudio, fromMs: Long, toMs: Long): FloatArray? {
        val stream = ext.createStream()
        try {
            stream.acceptWaveform(audio.slice(fromMs, toMs), audio.sampleRate)
            stream.inputFinished()
            return if (ext.isReady(stream)) ext.compute(stream) else null
        } finally {
            stream.release()
        }
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

/** sherpa получает [startMs, endMs), отрезки сохраняются только из [keepFromMs, keepToMs). */
internal data class DiarizationWindow(val startMs: Long, val endMs: Long, val keepFromMs: Long, val keepToMs: Long)

internal object DiarizationMapping {

    /**
     * Первый проход на длинной записи — окнами: sherpa держит буферы на весь переданный сигнал.
     * Спикеров между окнами связывает второй проход ([SherpaDiarizer.labelUnits]).
     */
    const val WINDOW_MS = 10 * 60 * 1000L
    const val WINDOW_OVERLAP_MS = 30_000L
    private const val WINDOW_SPEAKER_STRIDE = 1000

    fun windows(durationMs: Long, windowMs: Long = WINDOW_MS, overlapMs: Long = WINDOW_OVERLAP_MS): List<DiarizationWindow> {
        if (durationMs <= windowMs) return listOf(DiarizationWindow(0, durationMs, 0, durationMs))
        val out = ArrayList<DiarizationWindow>()
        var start = 0L
        while (true) {
            val end = minOf(durationMs, start + windowMs)
            val keepFrom = if (start == 0L) 0L else start + overlapMs / 2
            val keepTo = if (end == durationMs) durationMs else end - overlapMs / 2
            out += DiarizationWindow(start, end, keepFrom, keepTo)
            if (end == durationMs) return out
            start += windowMs - overlapMs
        }
    }

    /** Отрезки окна → время записи, обрезка по своей части перекрытия, свои номера спикеров у каждого окна. */
    fun stitch(segments: List<SpeakerSegment>, window: DiarizationWindow, index: Int): List<SpeakerSegment> =
        segments.mapNotNull { s ->
            val a = maxOf(window.startMs + s.startMs, window.keepFromMs)
            val b = minOf(window.startMs + s.endMs, window.keepToMs)
            if (b > a) SpeakerSegment(index * WINDOW_SPEAKER_STRIDE + s.speaker, a, b) else null
        }

    /** Участки короче — несколько отпечатков со сдвигом краёв (см. [SherpaDiarizer.labelUnits]). */
    const val SHORT_UNIT_MS = 1500L

    /** Сдвиги (влево, вправо) в мс; отрицательные — окно сужается, но не уже самого участка. */
    private val SHORT_UNIT_SHIFTS = listOf(0L to 0L, 100L to 0L, 0L to 100L, 100L to 100L, -100L to -100L)

    fun shortUnitCrops(unit: SpeechRegion, from: Long, to: Long, durationMs: Long): List<Pair<Long, Long>> =
        SHORT_UNIT_SHIFTS.map { (left, right) ->
            if (left < 0) {
                minOf(unit.startMs, from - left) to maxOf(unit.endMs, to + right)
            } else {
                maxOf(0L, from - left) to minOf(durationMs, to + right)
            }
        }

    /** Среднее нормированных векторов (направление голоса, а не громкость), null — если нечего усреднять. */
    fun average(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val out = FloatArray(vectors[0].size)
        for (v in vectors) {
            val norm = kotlin.math.sqrt(v.sumOf { it.toDouble() * it }).coerceAtLeast(1e-9)
            for (k in out.indices) out[k] += (v[k] / norm).toFloat() / vectors.size
        }
        return out
    }

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
