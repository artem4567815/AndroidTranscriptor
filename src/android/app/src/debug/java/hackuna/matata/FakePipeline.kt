package hackuna.matata

import hackuna.matata.core.model.AudioDecoder
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.PipelineEvent
import hackuna.matata.core.model.Stage
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptionPipeline
import hackuna.matata.core.model.TranscriptionResult
import hackuna.matata.core.model.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ТОЛЬКО DEBUG. Прогоняет мост целиком до готовности моделей: этап DECODE — настоящий
 * (проверяет декодер на телефоне), остальные — имитация с задержками и тестовой расшифровкой.
 * В release этого класса нет: ТЗ запрещает подменять работу модели готовой расшифровкой.
 */
class FakePipeline(private val decoder: AudioDecoder) : TranscriptionPipeline {

    override fun run(uri: String, config: PipelineConfig): Flow<PipelineEvent> = flow {
        val timings = LinkedHashMap<Stage, Long>()
        var stage = Stage.DECODE
        try {
            val t0 = System.currentTimeMillis()
            emit(PipelineEvent.Progress(Stage.DECODE, 0f))
            val audio = decoder.decode(uri)
            timings[Stage.DECODE] = System.currentTimeMillis() - t0
            emit(PipelineEvent.Progress(Stage.DECODE, 1f))
            emit(PipelineEvent.StageDone(Stage.DECODE, timings[Stage.DECODE]!!))

            for (s in listOf(Stage.PREPROCESS, Stage.VAD, Stage.DIARIZATION, Stage.ASR, Stage.MERGE, Stage.LLM)) {
                stage = s
                val ms = if (s == Stage.ASR || s == Stage.DIARIZATION) 1500L else 300L
                for (i in 1..10) {
                    delay(ms / 10)
                    emit(PipelineEvent.Progress(s, i / 10f))
                }
                timings[s] = ms
                emit(PipelineEvent.StageDone(s, ms))
            }

            val speakers = if (config.numSpeakers > 0) config.numSpeakers else 3
            val step = 4000L
            val utterances = (0 until (audio.durationMs / step).coerceIn(1, 200).toInt()).map { i ->
                Utterance(
                    speakerId = "speaker_${i % speakers + 1}",
                    startMs = i * step + 200,
                    endMs = minOf(audio.durationMs, (i + 1) * step - 200).coerceAtLeast(i * step + 201),
                    text = "[FakePipeline] Тестовая реплика ${i + 1}: мяу.",
                )
            }
            emit(
                PipelineEvent.Completed(
                    TranscriptionResult(
                        transcript = Transcript(utterances),
                        llmCorrected = null,
                        protocol = if (config.enableLlmProtocol) "[FakePipeline] Протокол появится после подключения LLM." else null,
                        speakerNames = emptyMap(),
                        timings = timings,
                        audioDurationMs = audio.durationMs,
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(PipelineEvent.Failed(stage, e))
        }
    }
}
