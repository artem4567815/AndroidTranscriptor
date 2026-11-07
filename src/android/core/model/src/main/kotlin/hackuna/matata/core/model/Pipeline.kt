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
}

interface SpeechRecognizer : Closeable {
    fun recognize(samples: FloatArray, sampleRate: Int): RecognizedSegment
}

interface LlmEngine : Closeable {
    suspend fun generate(prompt: String, maxTokens: Int): String
}

interface TranscriptPostProcessor {
    suspend fun protocol(t: Transcript): String
    suspend fun speakerRoles(t: Transcript): Map<String, String>
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
