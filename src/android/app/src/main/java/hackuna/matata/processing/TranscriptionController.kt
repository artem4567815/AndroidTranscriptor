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
import hackuna.matata.core.model.toErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var job: Job? = null

    val runningId: String? get() = (state.value as? State.Running)?.id

    /** @return false, если уже идёт другая обработка (одна за раз). */
    @Synchronized
    fun start(id: String, config: PipelineConfig): Boolean {
        if (job?.isActive == true) return false
        _state.value = State.Running(id, "", Stage.DECODE, 0f)
        ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java))
        job = scope.launch { runJob(id, config) }
        return true
    }

    fun cancel() {
        job?.cancel(CancellationException("Отменено пользователем"))
    }

    private suspend fun runJob(id: String, config: PipelineConfig) {
        var stage = Stage.DECODE
        val t0 = System.currentTimeMillis()
        try {
            val rec = repo.getRecording(id) ?: error("Запись $id не найдена")
            repo.setStatus(id, RecordingStatus.PROCESSING)
            _state.value = State.Running(id, rec.title, stage, 0f)
            pipelines.get().run(rec.sourcePath, config).collect { e ->
                when (e) {
                    is PipelineEvent.Progress -> {
                        stage = e.stage
                        _state.value = State.Running(id, rec.title, e.stage, e.fraction)
                        _events.emit(Event.Progress(id, e.stage, e.fraction))
                    }
                    is PipelineEvent.StageDone -> Unit
                    // Черновик без LLM: переживёт убийство процесса во время LLM (статус остаётся PROCESSING,
                    // а resetStaleProcessing при следующем запуске переведёт запись с результатом в DONE).
                    is PipelineEvent.TranscriptReady -> repo.saveResult(id, e.result, final = false)
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
                        _events.emit(Event.Progress(id, Stage.SAVE, 1f))
                        _events.emit(Event.Completed(id))
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                repo.setStatus(id, RecordingStatus.NEW)
                _events.emit(Event.Failed(id, stage, ErrorCode.CANCELLED, "Обработка отменена"))
            }
        } catch (e: Throwable) {
            val code = e.toErrorCode()
            Log.e(TAG, "processing failed at $stage", e)
            repo.setStatus(id, RecordingStatus.ERROR, code, e.message)
            _events.emit(Event.Failed(id, stage, code, e.message ?: e.toString()))
        } finally {
            _state.value = State.Idle
        }
    }

    private companion object {
        const val TAG = "Pipeline"
    }
}
