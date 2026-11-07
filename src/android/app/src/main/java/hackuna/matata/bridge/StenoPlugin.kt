package hackuna.matata.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import hackuna.matata.BuildTypeConfig
import hackuna.matata.core.audio.AudioRecorder
import hackuna.matata.core.data.PipelineSettingsRepository
import hackuna.matata.core.data.TranscriptRepository
import hackuna.matata.core.data.export.ExportFormat
import hackuna.matata.core.data.export.TranscriptExporter
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.StenoException
import hackuna.matata.core.model.toErrorCode
import hackuna.matata.ml.runtime.ModelManager
import hackuna.matata.processing.TranscriptionController
import hackuna.matata.storage.RecordingFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.UUID

/**
 * Kotlin-сторона контракта моста (см. src/web/src/native/steno.ts).
 * Меняется только вместе с steno.ts одним PR, с ревью ролей 1 и 3.
 *
 * Правила: каждый метод сразу уходит в корутину (Default — вычисления, IO — файлы);
 * ответ — call.resolve или call.reject(message, code) с кодом из [ErrorCode];
 * через мост не ходит аудио — только id, пути, настройки, прогресс и JSON.
 */
@CapacitorPlugin(
    name = "Steno",
    permissions = [
        Permission(alias = StenoPlugin.MIC, strings = [Manifest.permission.RECORD_AUDIO]),
        Permission(alias = StenoPlugin.NOTIFICATIONS, strings = [Manifest.permission.POST_NOTIFICATIONS]),
    ],
)
class StenoPlugin : Plugin(), KoinComponent {
    private val repo: TranscriptRepository by inject()
    private val models: ModelManager by inject()
    private val files: RecordingFiles by inject()
    private val controller: TranscriptionController by inject()
    private val settings: PipelineSettingsRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: AudioRecorder? = null
    private var recordingId: String? = null
    private var lastLevelAt = 0L
    private var pendingExport: Pair<String, String>? = null // имя файла → содержимое

    override fun load() {
        scope.launch {
            controller.events.collect { e ->
                when (e) {
                    is TranscriptionController.Event.Progress -> notifyListeners(
                        "progress",
                        JSObject().put("id", e.id).put("stage", e.stage.name).put("fraction", e.fraction.toDouble()),
                    )
                    is TranscriptionController.Event.Completed -> notifyListeners("completed", JSObject().put("id", e.id))
                    is TranscriptionController.Event.Analysis -> notifyListeners(
                        "analysis",
                        JSObject().put("id", e.id).put("running", e.running),
                    )
                    is TranscriptionController.Event.Failed -> notifyListeners(
                        "failed",
                        JSObject().put("id", e.id).put("stage", e.stage.name).put("code", e.code.name).put("message", e.message),
                    )
                }
            }
        }
    }

    override fun handleOnDestroy() {
        recorder?.stop()
        scope.cancel()
    }

    // ---- Модели ----

    @PluginMethod
    fun prepareModels(call: PluginCall) = io(call) {
        var lastSent = 0L
        val complete = models.prepare(allowMissing = BuildTypeConfig.ALLOW_MISSING_MODELS) { fraction, file ->
            val now = System.currentTimeMillis()
            if (now - lastSent > 100 || fraction >= 1f) {
                lastSent = now
                notifyListeners("modelsProgress", JSObject().put("fraction", fraction.toDouble()).put("file", file))
            }
        }
        if (!complete) Log.w(TAG, "Модели неполные — в debug будет FakePipeline")
        call.resolve(JSObject().put("ready", true))
    }

    @PluginMethod
    fun getModelsInfo(call: PluginCall) = io(call) { call.resolve(JsMapping.models(models.status())) }

    /** Значения параметров обработки по умолчанию (PipelineConfig) — Vue показывает их в настройках. */
    @PluginMethod
    fun getPipelineDefaults(call: PluginCall) = io(call) { call.resolve(JsMapping.pipelineDefaults()) }

    // ---- Записи ----

    @PluginMethod
    fun listRecordings(call: PluginCall) = io(call) { call.resolve(JsMapping.recordings(repo.listRecordings())) }

    @PluginMethod
    fun importAudio(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg", "video/mp4"))
        }
        startActivityForResult(call, intent, "onImportResult")
    }

    @ActivityCallback
    private fun onImportResult(call: PluginCall?, result: ActivityResult) {
        call ?: return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            reject(call, ErrorCode.CANCELLED, "Файл не выбран")
            return
        }
        io(call) { call.resolve(JsMapping.recording(files.import(uri))) }
    }

    @PluginMethod
    fun startRecording(call: PluginCall) {
        if (getPermissionState(MIC) != PermissionState.GRANTED) {
            requestPermissionForAlias(MIC, call, "onMicPermission")
            return
        }
        doStartRecording(call)
    }

    @PermissionCallback
    private fun onMicPermission(call: PluginCall) {
        if (getPermissionState(MIC) == PermissionState.GRANTED) doStartRecording(call)
        else reject(call, ErrorCode.NO_PERMISSION, "Нет разрешения на микрофон")
    }

    private fun doStartRecording(call: PluginCall) = io(call) {
        if (recorder?.isRecording == true) throw StenoException(ErrorCode.UNKNOWN, "Запись уже идёт")
        val id = UUID.randomUUID().toString()
        val r = AudioRecorder()
        r.start(files.newRecordingFile(id)) { level ->
            val now = System.currentTimeMillis()
            if (now - lastLevelAt >= 100) {
                lastLevelAt = now
                notifyListeners("recordingLevel", JSObject().put("level", level.toDouble()))
            }
        }
        recorder = r
        recordingId = id
        call.resolve()
    }

    @PluginMethod
    fun stopRecording(call: PluginCall) = io(call) {
        val r = recorder ?: throw StenoException(ErrorCode.UNKNOWN, "Запись не идёт")
        val id = recordingId!!
        recorder = null
        recordingId = null
        val duration = r.stop()
        call.resolve(JsMapping.recording(files.registerRecorded(id, files.newRecordingFile(id), duration)))
    }

    @PluginMethod
    fun deleteRecording(call: PluginCall) = io(call) {
        val id = call.requireString("id")
        if (controller.runningId == id) controller.cancel()
        repo.deleteRecording(id)
        files.deleteFiles(id)
        call.resolve()
    }

    // ---- Обработка ----

    @PluginMethod
    fun process(call: PluginCall) {
        // Разрешение на уведомления (Android 13+) желательно, но не обязательно для обработки.
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState(NOTIFICATIONS) == PermissionState.PROMPT) {
            requestPermissionForAlias(NOTIFICATIONS, call, "onNotificationPermission")
        } else {
            doProcess(call)
        }
    }

    @PermissionCallback
    private fun onNotificationPermission(call: PluginCall) = doProcess(call)

    private fun doProcess(call: PluginCall) = io(call) {
        val id = call.requireString("id")
        repo.getRecording(id) ?: throw StenoException(ErrorCode.UNKNOWN, "Запись $id не найдена")
        // База — значения по умолчанию из кода (PipelineConfig), а не прошлые сохранённые:
        // иначе новые пороги из кода не доходили бы до уже установленного приложения.
        // Сохраняем только как журнал «с какими параметрами обрабатывали последний раз».
        val config = JsMapping.pipelineConfig(call.getObject("options"), PipelineConfig())
        settings.save(config)
        if (!controller.start(id, config)) throw StenoException(ErrorCode.UNKNOWN, "Уже идёт другая обработка")
        call.resolve()
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        controller.cancel()
        call.resolve()
    }

    @PluginMethod
    fun getResult(call: PluginCall) = io(call) {
        val id = call.requireString("id")
        val r = repo.getResult(id) ?: throw StenoException(ErrorCode.UNKNOWN, "Нет расшифровки для $id")
        call.resolve(JsMapping.result(r, analysisPending = controller.analyzingId == id))
    }

    @PluginMethod
    fun updateUtterance(call: PluginCall) = io(call) {
        val index = call.getInt("index") ?: throw IllegalArgumentException("index обязателен")
        repo.updateUtterance(call.requireString("id"), index, call.getString("text"), call.getString("speakerId"))
        call.resolve()
    }

    @PluginMethod
    fun renameSpeaker(call: PluginCall) = io(call) {
        repo.renameSpeaker(call.requireString("id"), call.requireString("speakerId"), call.getString("name") ?: "")
        call.resolve()
    }

    // ---- Экспорт ----

    @PluginMethod
    fun exportResult(call: PluginCall) = io(call) {
        val (name, content, format) = buildExport(call)
        pendingExport = name to content
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = format.mimeType
            putExtra(Intent.EXTRA_TITLE, name)
        }
        activity.runOnUiThread { startActivityForResult(call, intent, "onExportResult") }
    }

    @ActivityCallback
    private fun onExportResult(call: PluginCall?, result: ActivityResult) {
        call ?: return
        val export = pendingExport
        pendingExport = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null || export == null) {
            call.resolve(JSObject().put("saved", false))
            return
        }
        io(call) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(export.second.toByteArray()) }
                ?: throw StenoException(ErrorCode.UNKNOWN, "Не удалось открыть файл для записи")
            call.resolve(JSObject().put("saved", true))
        }
    }

    @PluginMethod
    fun share(call: PluginCall) = io(call) {
        val (name, content, format) = buildExport(call)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, name).apply { writeText(content) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.runOnUiThread { activity.startActivity(Intent.createChooser(send, name)) }
        call.resolve()
    }

    private data class Export(val name: String, val content: String, val format: ExportFormat)

    private suspend fun buildExport(call: PluginCall): Export {
        val id = call.requireString("id")
        val format = ExportFormat.parse(call.getString("format"))
            ?: throw IllegalArgumentException("format: json | txt | srt")
        val rec = repo.getRecording(id) ?: throw StenoException(ErrorCode.UNKNOWN, "Запись $id не найдена")
        val result = repo.getResult(id) ?: throw StenoException(ErrorCode.UNKNOWN, "Нет расшифровки")
        val base = rec.title.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").ifBlank { "transcript" }
        return Export("$base.${format.extension}", TranscriptExporter.export(result, format), format)
    }

    // ---- Вспомогательное ----

    /** Запуск метода в IO-корутине с единым маппингом ошибок в коды моста. */
    private fun io(call: PluginCall, block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "${call.methodName} failed", e)
                reject(call, e.toErrorCode(), e.message ?: e.toString())
            }
        }
    }

    private fun reject(call: PluginCall, code: ErrorCode, message: String) = call.reject(message, code.name)

    private fun PluginCall.requireString(name: String): String =
        getString(name)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("$name обязателен")

    companion object {
        const val TAG = "StenoPlugin"
        const val MIC = "microphone"
        const val NOTIFICATIONS = "notifications"
    }
}
