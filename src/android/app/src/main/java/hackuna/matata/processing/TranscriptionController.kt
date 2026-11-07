package hackuna.matata.processing

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import hackuna.matata.BuildTypeConfig
import hackuna.matata.core.data.RecordingStatus
import hackuna.matata.core.data.TranscriptRepository
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.PipelineEvent
import hackuna.matata.core.model.Stage
import hackuna.matata.core.model.TranscriptionPipeline
import hackuna.matata.core.model.TranscriptionResult
import hackuna.matata.core.model.toErrorCode
import hackuna.matata.core.model.wantsAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface PipelineProvider {
    fun get(): TranscriptionPipeline
}

/**
 * Синглтон, владеющий единственной обработкой. Состояние — StateFlow (его слушает
 * TranscriptionService для уведомления), события — SharedFlow (их StenoPlugin пересылает во Vue).
 * Результат сразу пишется в Room, поэтому интерфейс восстанавливается после перезапуска.
 */
class TranscriptionController(
    private val context: Context,
    private val repo: TranscriptRepository,
    private val pipelines: PipelineProvider,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Running(val id: String, val title: String, val stage: Stage, val fraction: Float) : State
    }

    sealed interface Event {
        data class Progress(val id: String, val stage: Stage, val fraction: Float) : Event
        data class Completed(val id: String) : Event
        data class Failed(val id: String, val stage: Stage, val code: ErrorCode, val message: String) : Event

        /** LLM-анализ (итоги, имена спикеров) уже показанной расшифровки: [running] = идёт / закончился. */
        data class Analysis(val id: String, val running: Boolean) : Event
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var job: Job? = null

    /** Запись, для которой сейчас идёт LLM-анализ (расшифровка уже сохранена и показана). */
    @Volatile
    var analyzingId: String? = null
        private set

    val runningId: String? get() = (state.value as? State.Running)?.id

    /**
     * @return false, если уже идёт другая расшифровка (одна за раз). Идущий LLM-анализ прошлой записи
     * не мешает: он прерывается (у той записи просто не будет итогов), новая расшифровка важнее.
     */
    @Synchronized
    fun start(id: String, config: PipelineConfig): Boolean {
        val previous = job
        if (previous?.isActive == true) {
            if (analyzingId == null) return false
            previous.cancel(CancellationException("Новая обработка"))
        }
        _state.value = State.Running(id, "", Stage.DECODE, 0f)
        job = scope.launch {
            previous?.join()
            _state.value = State.Running(id, "", Stage.DECODE, 0f)
            ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java))
            runJob(id, config)
        }
        return true
    }

    fun cancel() {
        job?.cancel(CancellationException("Отменено пользователем"))
    }

    private suspend fun runJob(id: String, config: PipelineConfig) {
        var stage = Stage.DECODE
        val t0 = System.currentTimeMillis()
        var saved: TranscriptionResult? = null
        val me = currentCoroutineContext()[Job]
        try {
            val rec = repo.getRecording(id) ?: error("Запись $id не найдена")
            repo.setStatus(id, RecordingStatus.PROCESSING)
            _state.value = State.Running(id, rec.title, stage, 0f)
            val pipeline = pipelines.get()
            pipeline.run(rec.sourcePath, config).collect { e ->
                when (e) {
                    is PipelineEvent.Progress -> {
                        stage = e.stage
                        _state.value = State.Running(id, rec.title, e.stage, e.fraction)
                        _events.emit(Event.Progress(id, e.stage, e.fraction))
                    }
                    is PipelineEvent.StageDone -> Unit
                    is PipelineEvent.Failed -> {
                        stage = e.stage
                        throw e.error
                    }
                    is PipelineEvent.Completed -> {
                        stage = Stage.SAVE
                        _events.emit(Event.Progress(id, Stage.SAVE, 0f))
                        val s0 = System.currentTimeMillis()
                        repo.saveResult(id, e.result)
                        val saveMs = System.currentTimeMillis() - s0
                        // SAVE не входит в сохранённый RTF (он уже записан), но пишется в лог и timings.json.
                        val final = e.result.copy(timings = e.result.timings + (Stage.SAVE to saveMs))
                        Log.i(TAG, "stage=SAVE ms=$saveMs; total=${System.currentTimeMillis() - t0}ms rtf=${"%.3f".format(final.rtf)}")
                        if (BuildTypeConfig.WRITE_TIMINGS) TimingsWriter.write(context, rec.title, id, final)
                        saved = e.result
                        _events.emit(Event.Progress(id, Stage.SAVE, 1f))
                        _events.emit(Event.Completed(id))
                    }
                }
            }
            val transcript = saved?.transcript ?: return
            if (!config.wantsAnalysis(transcript)) return

            // Пользователь уже видит расшифровку; итоги и имена спикеров подтянутся по событию.
            stage = Stage.LLM
            analyzingId = id
            _state.value = State.Running(id, rec.title, Stage.LLM, 0f)
            _events.emit(Event.Analysis(id, running = true))
            pipeline.analyze(transcript, config)?.let { repo.saveAnalysis(id, it) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Отмена во время анализа: расшифровка уже сохранена — просто без итогов.
                if (saved == null) {
                    repo.setStatus(id, RecordingStatus.NEW)
                    _events.emit(Event.Failed(id, stage, ErrorCode.CANCELLED, "Обработка отменена"))
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "processing failed at $stage", e)
            if (saved == null) {
                val code = e.toErrorCode()
                repo.setStatus(id, RecordingStatus.ERROR, code, e.message)
                _events.emit(Event.Failed(id, stage, code, e.message ?: e.toString()))
            }
        } finally {
            withContext(NonCancellable) {
                if (analyzingId == id) {
                    analyzingId = null
                    _events.emit(Event.Analysis(id, running = false))
                }
            }
            // Новая обработка могла уже стартовать (start() прервал этот анализ) — её состояние не трогаем.
            if (job === me) _state.value = State.Idle
        }
    }

    private companion object {
        const val TAG = "Pipeline"
    }
}
