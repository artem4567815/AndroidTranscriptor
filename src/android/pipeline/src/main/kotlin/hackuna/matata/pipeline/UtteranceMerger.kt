package hackuna.matata.pipeline

import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeakerText
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.Utterance

/**
 * Склейка реплик (чистая логика):
 *  1. сортировка по времени;
 *  2. выбрасываются пустые сегменты (без букв и цифр — тишина, шум, одиночная пунктуация);
 *  3. соседние сегменты одного спикера с паузой меньше mergeGapMs объединяются;
 *  4. спикеры перенумеровываются по первому появлению: speaker_1, speaker_2, ...;
 *  5. гарантируется 0 <= start_ms < end_ms.
 */
object UtteranceMerger {

    fun merge(segments: List<SpeakerText>, config: PipelineConfig): Transcript {
        val sorted = segments
            .map { it.copy(startMs = it.startMs.coerceAtLeast(0), text = normalizeSpaces(it.text)) }
            .filter { s -> s.text.any { it.isLetterOrDigit() } }
            .sortedWith(compareBy<SpeakerText> { it.startMs }.thenBy { it.endMs })

        val merged = ArrayList<SpeakerText>(sorted.size)
        for (s in sorted) {
            val last = merged.lastOrNull()
            if (last != null && last.speaker == s.speaker && s.startMs - last.endMs < config.mergeGapMs) {
                merged[merged.lastIndex] = last.copy(
                    endMs = maxOf(last.endMs, s.endMs),
                    text = joinText(last.text, s.text),
                )
            } else {
                merged += s
            }
        }

        val ids = LinkedHashMap<Int, String>()
        return Transcript(
            merged.map { s ->
                val id = ids.getOrPut(s.speaker) { "speaker_${ids.size + 1}" }
                Utterance(
                    speakerId = id,
                    startMs = s.startMs,
                    endMs = maxOf(s.endMs, s.startMs + 1),
                    text = s.text,
                )
            },
        )
    }

    private fun normalizeSpaces(text: String) = text.trim().replace(Regex("\\s+"), " ")

    private fun joinText(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a $b"
    }
}
