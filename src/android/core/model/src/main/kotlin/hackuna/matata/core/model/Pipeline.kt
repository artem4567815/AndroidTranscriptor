package hackuna.matata.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.io.Closeable

enum class Stage { DECODE, PREPROCESS, VAD, DIARIZATION, ASR, MERGE, LLM, SAVE }

/**
 * Параметры пайплайна. Значения по умолчанию продублированы в config/pipeline.default.json —
 * Python-стенд использует те же параметры. Меняете здесь — меняйте и там.
 */
@Serializable
data class PipelineConfig(
    val numSpeakers: Int = 0, // 0 = определить автоматически
    val clusterThreshold: Float = 0.5f,
    val mergeGapMs: Long = 700, // склеивать реплики одного спикера с паузой меньше
    val maxSegmentMs: Long = 20_000, // длиннее — резать по паузам VAD
    val segmentPaddingMs: Long = 150,
    val minUtteranceMs: Long = 200,
    val numThreads: Int = 4,
    val enableDenoise: Boolean = false,
    val enableLlmProtocol: Boolean = true,
    val enableLlmCorrection: Boolean = false,
    /** Расширение контракта: порог Silero VAD (SettingsPage). */
    val vadThreshold: Float = 0.5f,
    /**
     * Шаг окна сегментации pyannote в долях окна (окно 10 с): 0.1 → шаг 1 с.
     * Больше шаг — меньше окон и отпечатков голоса → диаризация быстрее, но границы грубее.
     * Оставлен 0.1 (шаг 1 с, как в pyannote): 0.2–0.25 быстрее в ~1,8–2×, но на тестовых записях
     * число спикеров нестабильно (docs/TESTS.md). Менять только по DER на своих размеченных записях.
     */
    val diarizationWindowShift: Float = 0.1f,
    /**
     * Второй проход диаризации: участки речи (VAD) группируются по отпечаткам голоса.
     * На коротких репликах и при «размытых» голосах заметно точнее, чем один sherpa (docs/TESTS.md).
     */
    val regroupSpeakers: Boolean = true,
    /** Запас по краям участка для отпечатка голоса, мс (только в тишину, не в соседний участок). */
    val embeddingPaddingMs: Long = 200,
    /** Режим «Авто»: если разбиение на группы хуже этого «силуэта», считаем, что говорит один человек. */
    val minSpeakerSilhouette: Float = 0.10f,
)

data class TranscriptionResult(
    val transcript: Transcript, // сырой ASR — идёт в строгий экспорт
    val llmCorrected: Transcript?, // правка LLM — отдельно, только по переключателю
    val protocol: String?, // протокол заседания от LLM
    val speakerNames: Map<String, String>, // speaker_1 -> «Председатель»
    val timings: Map<Stage, Long>,
    val audioDurationMs: Long,
) {
    val rtf: Double get() = if (audioDurationMs <= 0) 0.0 else timings.values.sum().toDouble() / audioDurationMs
}

sealed interface PipelineEvent {
    data class Progress(val stage: Stage, val fraction: Float) : PipelineEvent
    data class StageDone(val stage: Stage, val millis: Long) : PipelineEvent
    /**
     * Расшифровка готова, дальше идёт долгий и тяжёлый по памяти этап LLM. Контроллер сохраняет
     * её сразу: если систему убьют за нехватку памяти во время LLM, расшифровка не потеряется.
     */
    data class TranscriptReady(val result: TranscriptionResult) : PipelineEvent
    data class Completed(val result: TranscriptionResult) : PipelineEvent
    data class Failed(val stage: Stage, val error: Throwable) : PipelineEvent
}

// Интерфейсы. uri — String, чтобы core/model не зависел от Android.

/** Любой формат → 16 кГц моно. */
interface AudioDecoder {
    suspend fun decode(uri: String): PcmAudio
}

interface AudioPreprocessor {
    fun process(audio: PcmAudio): PcmAudio
}

interface VoiceActivityDetector : Closeable {
    fun detect(audio: PcmAudio): List<SpeechRegion>
}

interface Diarizer : Closeable {
    fun diarize(audio: PcmAudio, config: PipelineConfig, onProgress: (Float) -> Unit): List<SpeakerSegment>

    /**
     * Второй проход: номер спикера для каждого готового участка речи по отпечаткам голоса
     * (участки отсортированы и не пересекаются). null — реализация не умеет, тогда остаются
     * метки первого прохода ([diarize]).
     */
    fun labelUnits(audio: PcmAudio, units: List<SpeechRegion>, config: PipelineConfig): List<Int>? = null
}

interface SpeechRecognizer : Closeable {
    fun recognize(samples: FloatArray, sampleRate: Int): RecognizedSegment
}

interface LlmEngine : Closeable {
    /** [system] — инструкция модели (системное сообщение), [prompt] — сам запрос с данными. */
    suspend fun generate(prompt: String, maxTokens: Int, system: String? = null): String
}

/**
 * Протокол и роли участников (speaker_N → «Ведущий»). Получаются ОДНИМ запросом к LLM:
 * модель читает расшифровку один раз — это самая долгая часть генерации на телефоне.
 */
data class MeetingProtocol(val text: String, val roles: Map<String, String>)

interface TranscriptPostProcessor {
    suspend fun protocol(t: Transcript): MeetingProtocol
    suspend fun correct(t: Transcript): Transcript
}

interface TranscriptionPipeline {
    fun run(uri: String, config: PipelineConfig): Flow<PipelineEvent>
}

// Фабрики: каждый движок создаётся лениво и закрывается сразу после своего этапа,
// модели загружаются по очереди (диаризация → ASR → LLM), иначе телефон упадёт по памяти.

fun interface VadFactory {
    fun create(config: PipelineConfig): VoiceActivityDetector
}

fun interface DiarizerFactory {
    fun create(config: PipelineConfig): Diarizer
}

fun interface SpeechRecognizerFactory {
    fun create(config: PipelineConfig): SpeechRecognizer
}

fun interface LlmEngineFactory {
    fun create(config: PipelineConfig): LlmEngine
}

fun interface PostProcessorFactory {
    fun create(engine: LlmEngine): TranscriptPostProcessor
}
