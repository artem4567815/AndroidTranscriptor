package hackuna.matata.pipeline

import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeakerSegment
import hackuna.matata.core.model.SpeechRegion

/** Кусок аудио для ASR: границы реплики [startMs, endMs) и границы вырезки с паддингом. */
data class AsrChunk(
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val audioStartMs: Long,
    val audioEndMs: Long,
)

/**
 * Нарезка сегментов говорящих для ASR:
 *  - сегменты короче minUtteranceMs отбрасываются (обрывки диаризации);
 *  - сегменты длиннее maxSegmentMs режутся по паузам VAD (середина паузы), при отсутствии пауз — жёстко;
 *  - пересечения отрезков разных спикеров разрешаются (см. [resolveOverlaps]), чтобы один и тот же
 *    кусок аудио не распознавался дважды и слова не дублировались в двух репликах;
 *  - к каждому куску добавляется паддинг segmentPaddingMs в пределах записи.
 */
object SegmentPlanner {

    fun plan(
        segments: List<SpeakerSegment>,
        speech: List<SpeechRegion>,
        config: PipelineConfig,
        durationMs: Long,
    ): List<AsrChunk> {
        val cuts = pauseMidpoints(speech)
        return resolveOverlaps(segments)
            .filter { it.endMs - it.startMs >= config.minUtteranceMs }
            .flatMap { split(it, cuts, config.maxSegmentMs) }
            .map { s ->
                AsrChunk(
                    speaker = s.speaker,
                    startMs = s.startMs,
                    endMs = s.endMs,
                    audioStartMs = (s.startMs - config.segmentPaddingMs).coerceAtLeast(0),
                    audioEndMs = (s.endMs + config.segmentPaddingMs).coerceAtMost(durationMs),
                )
            }
    }

    /**
     * Делает отрезки непересекающимися — каждый момент записи принадлежит одному спикеру:
     *  - тот же спикер — отрезки сливаются;
     *  - частичное пересечение разных спикеров — режем посередине пересечения;
     *  - короткий отрезок целиком внутри длинного (реплика «да» поверх чужой речи) —
     *    длинный разрезается на две части вокруг короткого.
     * Результат отсортирован по времени. Слишком короткие обрезки потом отбросит фильтр minUtteranceMs.
     */
    fun resolveOverlaps(segments: List<SpeakerSegment>): List<SpeakerSegment> {
        val out = ArrayList<SpeakerSegment>(segments.size)
        for (seg in segments.sortedWith(compareBy<SpeakerSegment> { it.startMs }.thenBy { it.endMs })) {
            val prev = out.lastOrNull()
            // Начало предыдущего могли сдвинуть при прошлом разрезе — не залезаем левее него.
            var s = if (prev != null && seg.startMs < prev.startMs) seg.copy(startMs = prev.startMs) else seg
            if (s.endMs <= s.startMs) continue
            when {
                prev == null || s.startMs >= prev.endMs -> out += s
                s.speaker == prev.speaker ->
                    out[out.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, s.endMs))
                s.endMs < prev.endMs -> {
                    // Короткий внутри длинного: длинный = [до] + короткий + [после].
                    out[out.lastIndex] = prev.copy(endMs = s.startMs)
                    out += s
                    out += prev.copy(startMs = s.endMs)
                }
                else -> {
                    val mid = (s.startMs + prev.endMs) / 2
                    out[out.lastIndex] = prev.copy(endMs = mid)
                    s = s.copy(startMs = mid)
                    out += s
                }
            }
        }
        return out.filter { it.endMs > it.startMs }
    }

    /** Если диаризация ничего не нашла, а VAD нашёл речь — один спикер на всю речь. */
    fun fallbackSegments(speech: List<SpeechRegion>): List<SpeakerSegment> =
        speech.map { SpeakerSegment(0, it.startMs, it.endMs) }

    internal fun pauseMidpoints(speech: List<SpeechRegion>): List<Long> =
        speech.sortedBy { it.startMs }.zipWithNext()
            .filter { (a, b) -> b.startMs > a.endMs }
            .map { (a, b) -> (a.endMs + b.startMs) / 2 }

    internal fun split(s: SpeakerSegment, cuts: List<Long>, maxMs: Long): List<SpeakerSegment> {
        if (s.endMs - s.startMs <= maxMs) return listOf(s)
        val out = ArrayList<SpeakerSegment>()
        var cur = s.startMs
        val inside = cuts.filter { it > s.startMs && it < s.endMs }
        while (s.endMs - cur > maxMs) {
            // Самая поздняя пауза, при которой кусок не длиннее maxMs и не короче четверти.
            val cut = inside.lastOrNull { it > cur + maxMs / 4 && it <= cur + maxMs } ?: (cur + maxMs)
            out += SpeakerSegment(s.speaker, cur, cut)
            cur = cut
        }
        out += SpeakerSegment(s.speaker, cur, s.endMs)
        return out
    }
}
