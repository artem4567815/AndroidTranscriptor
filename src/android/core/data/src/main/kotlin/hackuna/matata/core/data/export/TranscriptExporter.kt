package hackuna.matata.core.data.export

import hackuna.matata.core.data.StoredResult
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptJson
import hackuna.matata.core.model.Utterance

enum class ExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    TXT("txt", "text/plain"),
    SRT("srt", "application/x-subrip"),
    ;

    companion object {
        fun parse(value: String?): ExportFormat? = entries.find { it.extension.equals(value, ignoreCase = true) }
    }
}

/**
 * Экспорт расшифровки. Основа — всегда сырой ASR ([StoredResult.transcript]) с ручными правками:
 * правка LLM в экспорт не попадает, пока команда не докажет на стенде, что она снижает WER.
 * Строгий JSON формирует только этот класс (через [TranscriptJson]).
 */
object TranscriptExporter {

    fun export(result: StoredResult, format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> json(result.transcript)
        ExportFormat.TXT -> txt(result.transcript, result.speakerNames, result.protocol)
        ExportFormat.SRT -> srt(result.transcript, result.speakerNames)
    }

    /** Строго по формату организаторов: только ключ transcript и четыре поля реплики. */
    fun json(t: Transcript): String = TranscriptJson.encode(sanitize(t), pretty = true)

    fun txt(t: Transcript, names: Map<String, String> = emptyMap(), protocol: String? = null): String = buildString {
        for (u in sanitize(t).transcript) {
            append('[').append(clock(u.startMs, '.')).append(" – ").append(clock(u.endMs, '.')).append("] ")
            append(label(u.speakerId, names)).append(": ").append(u.text.trim()).append('\n')
        }
        if (!protocol.isNullOrBlank()) {
            append("\n---\n").append(protocol.trim()).append('\n')
        }
    }

    fun srt(t: Transcript, names: Map<String, String> = emptyMap()): String = buildString {
        sanitize(t).transcript.forEachIndexed { i, u ->
            append(i + 1).append('\n')
            append(clock(u.startMs, ',')).append(" --> ").append(clock(u.endMs, ',')).append('\n')
            append(label(u.speakerId, names)).append(": ").append(u.text.trim()).append("\n\n")
        }
    }

    /** Гарантии формата: сортировка по времени, start_ms < end_ms, неотрицательное время. */
    fun sanitize(t: Transcript): Transcript = Transcript(
        t.transcript
            .map { u ->
                val start = u.startMs.coerceAtLeast(0)
                u.copy(startMs = start, endMs = maxOf(u.endMs, start + 1))
            }
            .sortedWith(compareBy<Utterance> { it.startMs }.thenBy { it.endMs }),
    )

    /** 3_725_250 → "01:02:05,250" (SRT) или "01:02:05.250" (TXT). */
    fun clock(ms: Long, sep: Char): String {
        val total = ms.coerceAtLeast(0)
        val h = total / 3_600_000
        val m = total / 60_000 % 60
        val s = total / 1000 % 60
        val milli = total % 1000
        return "%02d:%02d:%02d%c%03d".format(h, m, s, sep, milli)
    }

    private fun label(speakerId: String, names: Map<String, String>): String {
        val name = names[speakerId]?.takeIf { it.isNotBlank() }
        return if (name != null) "$name ($speakerId)" else speakerId
    }
}
