package hackuna.matata.bridge

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import hackuna.matata.core.data.RecordingInfo
import hackuna.matata.core.data.StoredResult
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.TranscriptJson
import hackuna.matata.ml.runtime.ModelStatus

/** Преобразования Kotlin → JSON моста. Поля совпадают с src/web/src/native/steno.ts. */
internal object JsMapping {

    fun recording(r: RecordingInfo) = JSObject().apply {
        put("id", r.id)
        put("title", r.title)
        put("durationMs", r.durationMs)
        put("createdAt", r.createdAt)
        put("status", r.status.name)
        put("audioPath", r.audioPath)
    }

    fun recordings(items: List<RecordingInfo>) = JSObject().apply {
        put("items", JSArray().apply { items.forEach { put(recording(it)) } })
    }

    /** Расшифровка уходит во Vue тем же строгим JSON, что и в экспорт. */
    fun result(r: StoredResult) = JSObject().apply {
        put("recordingId", r.recordingId)
        put("transcript", JSObject(TranscriptJson.encode(r.transcript, pretty = false)))
        r.llmCorrected?.let { put("llmCorrected", JSObject(TranscriptJson.encode(it, pretty = false))) }
        r.protocol?.let { put("protocol", it) }
        put("speakerNames", JSObject().apply { r.speakerNames.forEach { (k, v) -> put(k, v) } })
        put("rtf", r.rtf)
    }

    fun models(items: List<ModelStatus>) = JSObject().apply {
        put(
            "items",
            JSArray().apply {
                items.forEach { s ->
                    put(
                        JSObject().apply {
                            put("id", s.file.id)
                            put("purpose", s.file.purpose.ifEmpty { s.file.path })
                            put("version", s.file.version)
                            put("sizeBytes", s.file.sizeBytes)
                            put("license", s.file.license)
                            put("present", s.present)
                        },
                    )
                }
            },
        )
    }

    /** PipelineOptions из Vue поверх значений по умолчанию PipelineConfig. */
    fun pipelineConfig(o: JSObject?, base: PipelineConfig = PipelineConfig()): PipelineConfig {
        if (o == null) return base
        return base.copy(
            numSpeakers = o.optInt("numSpeakers", base.numSpeakers).coerceIn(0, 20),
            enableLlmProtocol = o.optBoolean("enableLlmProtocol", base.enableLlmProtocol),
            enableLlmCorrection = o.optBoolean("enableLlmCorrection", base.enableLlmCorrection),
            numThreads = o.optInt("numThreads", base.numThreads).coerceIn(1, 16),
            clusterThreshold = o.optDouble("clusterThreshold", base.clusterThreshold.toDouble()).toFloat(),
            vadThreshold = o.optDouble("vadThreshold", base.vadThreshold.toDouble()).toFloat(),
        )
    }
}
