package hackuna.matata.processing

import android.content.Context
import android.util.Log
import hackuna.matata.core.model.TranscriptionResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Только debug: timings.json для src/eval/report.py (RTF на телефоне).
 * Файлы: Android/data/hackuna.matata/files/exports/<id>.timings.json (adb pull).
 */
object TimingsWriter {
    fun write(context: Context, title: String, id: String, r: TranscriptionResult) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val json = buildJsonObject {
                put("recording_id", id)
                put("title", title)
                put("audio_duration_ms", r.audioDurationMs)
                put("rtf", r.rtf)
                putJsonObject("timings_ms") { r.timings.forEach { (k, v) -> put(k.name, v) } }
            }
            File(dir, "$id.timings.json").writeText(json.toString())
        }.onFailure { Log.w("Pipeline", "timings.json не записан", it) }
    }
}
