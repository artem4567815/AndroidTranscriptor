package hackuna.matata.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import hackuna.matata.core.audio.WavWriter
import hackuna.matata.core.audio.probeDurationMs
import hackuna.matata.core.data.RecordingInfo
import hackuna.matata.core.data.RecordingStatus
import hackuna.matata.core.data.TranscriptRepository
import hackuna.matata.core.model.AudioDecoder
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.StenoException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Файлы записей во внутренней памяти: filesDir/recordings/<id>.<ext>.
 * Импорт копирует выбранный файл к себе (content:// URI может перестать работать),
 * для форматов, которые WebView не играет, делает WAV-копию для плеера.
 */
class RecordingFiles(
    private val context: Context,
    private val repo: TranscriptRepository,
    private val decoder: AudioDecoder,
) {
    val dir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newRecordingFile(id: String): File = File(dir, "$id.wav")

    /** Блокирующие операции — вызывать из Dispatchers.IO. */
    suspend fun import(uri: Uri): RecordingInfo {
        val id = UUID.randomUUID().toString()
        val name = displayName(uri) ?: "Импорт ${now()}"
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { extFromMime(uri) }
        val source = File(dir, "$id.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { input.copyTo(it, 1 shl 20) }
            } ?: throw StenoException(ErrorCode.DECODE_FAILED, "Не удалось открыть файл")

            var duration = probeDurationMs(context, source.absolutePath)
            val playable = if (ext in WEBVIEW_PLAYABLE) {
                source
            } else {
                // Редкий формат (amr, 3gp, wma…): WAV-копия для <audio>; заодно проверка, что декодируется.
                val pcm = decoder.decode(source.absolutePath)
                duration = pcm.durationMs
                File(dir, "$id.play.wav").also { WavWriter.write(it, pcm) }
            }
            if (duration <= 0) duration = decoder.decode(source.absolutePath).durationMs
            val info = RecordingInfo(
                id = id,
                title = name,
                sourcePath = source.absolutePath,
                audioPath = playable.absolutePath,
                durationMs = duration,
                createdAt = System.currentTimeMillis(),
                status = RecordingStatus.NEW,
            )
            repo.insertRecording(info)
            return info
        } catch (e: Throwable) {
            deleteFiles(id)
            throw if (e is StenoException) e else StenoException(ErrorCode.DECODE_FAILED, e.message ?: "Импорт не удался", e)
        }
    }

    suspend fun registerRecorded(id: String, file: File, durationMs: Long): RecordingInfo {
        val info = RecordingInfo(
            id = id,
            title = "Запись ${now()}",
            sourcePath = file.absolutePath,
            audioPath = file.absolutePath,
            durationMs = durationMs,
            createdAt = System.currentTimeMillis(),
            status = RecordingStatus.NEW,
        )
        repo.insertRecording(info)
        return info
    }

    fun deleteFiles(id: String) {
        dir.listFiles { f -> f.name.startsWith(id) }?.forEach { it.delete() }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.onFailure { Log.w(TAG, "display name", it) }.getOrNull()

    private fun extFromMime(uri: Uri): String = when (context.contentResolver.getType(uri)) {
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/amr", "audio/3gpp" -> "amr"
        else -> "bin"
    }

    private fun now() = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru")).format(Date())

    private companion object {
        const val TAG = "RecordingFiles"
        /** Что гарантированно играет Chromium WebView без конвертации. */
        val WEBVIEW_PLAYABLE = setOf("mp3", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "flac", "webm")
    }
}
