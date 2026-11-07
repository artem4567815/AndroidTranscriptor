package hackuna.matata.pipeline

import android.util.Log
import hackuna.matata.core.model.Analysis
import hackuna.matata.core.model.AudioDecoder
import hackuna.matata.core.model.AudioPreprocessor
import hackuna.matata.core.model.DiarizerFactory
import hackuna.matata.core.model.LlmEngineFactory
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.PipelineEvent
import hackuna.matata.core.model.PostProcessorFactory
import hackuna.matata.core.model.RecognizedSegment
import hackuna.matata.core.model.SpeakerText
import hackuna.matata.core.model.SpeechRecognizerFactory
import hackuna.matata.core.model.Stage
import hackuna.matata.core.model.StenoException
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptionPipeline
import hackuna.matata.core.model.TranscriptionResult
import hackuna.matata.core.model.VadFactory
import hackuna.matata.core.model.wantsAnalysis
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
                diarizerFactory.create(config).use { d ->
                    val found = d.diarize(audio, config) { f ->
                        trySend(PipelineEvent.Progress(Stage.DIARIZATION, (f * 0.8f).coerceIn(0f, 0.8f)))
                    }
                    // Участки речи (VAD, разрезанные по сменам спикера из первого прохода) + второй проход.
                    val units = SegmentPlanner.speechUnits(speech, found)
                    val labels = if (config.regroupSpeakers && units.size >= 2) d.labelUnits(audio, units, config) else null
                    if (units.isEmpty()) found else SegmentPlanner.labelUnits(units, labels, found)
                }.ifEmpty { SegmentPlanner.fallbackSegments(speech) }
            }
            log("diarization: segments=${speakers.size} speakers=${speakers.map { it.speaker }.distinct().size}")

            val texts = step(Stage.ASR) {
                val chunks = SegmentPlanner.plan(speakers, speech, config, audio.durationMs)
                asrFactory.create(config).use { asr ->
                    chunks.mapIndexed { i, c ->
                        currentCoroutineContext().ensureActive()
                        val r = asr.recognize(audio.slice(c.audioStartMs, c.audioEndMs), audio.sampleRate)
                        send(PipelineEvent.Progress(Stage.ASR, (i + 1).toFloat() / chunks.size))
                        speakerText(c, r)
                    }
                }
            }

            val transcript = step(Stage.MERGE) { UtteranceMerger.merge(texts, config) }

            // LLM здесь нет: расшифровка показывается сразу, анализ — отдельно (analyze), уже после показа.
            send(
                PipelineEvent.Completed(
                    TranscriptionResult(
                        transcript = transcript,
                        llmCorrected = null,
                        protocol = null,
                        speakerNames = emptyMap(),
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

    /**
     * Границы реплики — границы участка речи (VAD), а не метки токенов ASR. GigaAM отдаёт только начало
     * токена; обрезка конца по «последний токен + 200 мс» отрезала последнее слово и ухудшила DER
     * (игра 7,6% → 18,9%, планёрка 1,0% → 2,3%, docs/TESTS.md). Токены остаются в [RecognizedSegment].
     */
    private fun speakerText(chunk: AsrChunk, recognized: RecognizedSegment) =
        SpeakerText(chunk.speaker, chunk.startMs, chunk.endMs, recognized.text)

    /**
     * Анализ готовой расшифровки: имена спикеров из самопредставлений ([SpeakerNames], без LLM — работают и при
     * сбое LLM) + итоги и правка от LLM. Сбой LLM не трогает расшифровку, вместо итогов — причина
     * («Итоги не составлены: мало свободной памяти…»).
     */
    override suspend fun analyze(transcript: Transcript, config: PipelineConfig): Analysis? {
        if (!config.wantsAnalysis(transcript)) return null
        val t0 = nowMs()
        val names = SpeakerNames.fromIntroductions(transcript)
        val result = try {
            llmFactory.create(config).use { engine ->
                val pp = postProcessorFactory.create(engine)
                val summary = if (config.enableLlmProtocol) pp.protocol(transcript) else null
                Analysis(
                    protocol = summary?.takeIf { it.isNotBlank() },
                    roles = names,
                    corrected = if (config.enableLlmCorrection) pp.correct(transcript) else null,
                    millis = 0,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("LLM skipped: $e")
            val reason = if (e is StenoException) e.message else "ошибка модели (${e.message ?: e.javaClass.simpleName})"
            Analysis(if (config.enableLlmProtocol) "Итоги не составлены: $reason" else null, names, null, 0)
        }
        val ms = nowMs() - t0
        log("stage=${Stage.LLM} ms=$ms")
        return result.copy(millis = ms)
    }

    companion object {
        const val TAG = "Pipeline"
    }
}
