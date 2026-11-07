package hackuna.matata.pipeline

import android.util.Log
import hackuna.matata.core.model.AudioDecoder
import hackuna.matata.core.model.AudioPreprocessor
import hackuna.matata.core.model.DiarizerFactory
import hackuna.matata.core.model.LlmEngineFactory
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.PipelineEvent
import hackuna.matata.core.model.PostProcessorFactory
import hackuna.matata.core.model.SpeakerText
import hackuna.matata.core.model.SpeechRecognizerFactory
import hackuna.matata.core.model.Stage
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptionPipeline
import hackuna.matata.core.model.TranscriptionResult
import hackuna.matata.core.model.VadFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Конвейер: декодирование 16 кГц моно → предобработка → VAD → диаризация → ASR по сегментам
 * говорящих → склейка реплик → LLM-постобработка. Сохранение (SAVE) делает контроллер в app.
 *
 * Каждый движок создаётся фабрикой непосредственно перед своим этапом и закрывается сразу после
 * (`use {}`): модели в памяти по очереди. Время этапов пишется в лог с тегом `Pipeline` и в timings.
 */
class DefaultTranscriptionPipeline(
    private val decoder: AudioDecoder,
    private val preprocessor: AudioPreprocessor,
    private val vadFactory: VadFactory,
    private val diarizerFactory: DiarizerFactory,
    private val asrFactory: SpeechRecognizerFactory,
    private val llmFactory: LlmEngineFactory,
    private val postProcessorFactory: PostProcessorFactory,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : TranscriptionPipeline {

    override fun run(uri: String, config: PipelineConfig): Flow<PipelineEvent> = channelFlow {
        val timings = LinkedHashMap<Stage, Long>()
        var stage = Stage.DECODE

        suspend fun <T> step(s: Stage, block: suspend () -> T): T {
            stage = s
            send(PipelineEvent.Progress(s, 0f))
            val t0 = nowMs()
            val result = block()
            val dt = nowMs() - t0
            timings[s] = dt
            log("stage=$s ms=$dt")
            send(PipelineEvent.Progress(s, 1f))
            send(PipelineEvent.StageDone(s, dt))
            return result
        }

        try {
            var audio: PcmAudio = step(Stage.DECODE) { decoder.decode(uri) }
            log("audio duration=${audio.durationMs}ms")
            audio = step(Stage.PREPROCESS) { preprocessor.process(audio) }

            val speech = step(Stage.VAD) { vadFactory.create(config).use { it.detect(audio) } }
            log("vad: regions=${speech.size} speechMs=${speech.sumOf { it.endMs - it.startMs }}")

            val speakers = step(Stage.DIARIZATION) {
                val found = diarizerFactory.create(config).use { d ->
                    d.diarize(audio, config) { f -> trySend(PipelineEvent.Progress(Stage.DIARIZATION, f.coerceIn(0f, 1f))) }
                }
                found.ifEmpty { SegmentPlanner.fallbackSegments(speech) }
            }
            log("diarization: segments=${speakers.size} speakers=${speakers.map { it.speaker }.distinct().size}")

            val texts = step(Stage.ASR) {
                val chunks = SegmentPlanner.plan(speakers, speech, config, audio.durationMs)
                asrFactory.create(config).use { asr ->
                    chunks.mapIndexed { i, c ->
                        currentCoroutineContext().ensureActive()
                        val r = asr.recognize(audio.slice(c.audioStartMs, c.audioEndMs), audio.sampleRate)
                        send(PipelineEvent.Progress(Stage.ASR, (i + 1).toFloat() / chunks.size))
                        SpeakerText(c.speaker, c.startMs, c.endMs, r.text)
                    }
                }
            }

            val transcript = step(Stage.MERGE) { UtteranceMerger.merge(texts, config) }

            val llm = step(Stage.LLM) { runLlm(transcript, config) }

            send(
                PipelineEvent.Completed(
                    TranscriptionResult(
                        transcript = transcript,
                        llmCorrected = llm.corrected,
                        protocol = llm.protocol,
                        speakerNames = llm.roles,
                        timings = timings.toMap(),
                        audioDurationMs = audio.durationMs,
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("failed at $stage: $e")
            send(PipelineEvent.Failed(stage, e))
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.Default)

    private data class LlmOutput(val protocol: String?, val roles: Map<String, String>, val corrected: Transcript?)

    /** LLM — необязательный этап: его сбой не валит обработку, сырой ASR остаётся. */
    private suspend fun runLlm(t: Transcript, config: PipelineConfig): LlmOutput {
        if (t.transcript.isEmpty() || (!config.enableLlmProtocol && !config.enableLlmCorrection)) {
            return LlmOutput(null, emptyMap(), null)
        }
        return try {
            llmFactory.create(config).use { engine ->
                val pp = postProcessorFactory.create(engine)
                LlmOutput(
                    protocol = if (config.enableLlmProtocol) pp.protocol(t) else null,
                    roles = if (config.enableLlmProtocol) pp.speakerRoles(t) else emptyMap(),
                    corrected = if (config.enableLlmCorrection) pp.correct(t) else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("LLM skipped: $e")
            LlmOutput(null, emptyMap(), null)
        }
    }

    companion object {
        const val TAG = "Pipeline"
    }
}
