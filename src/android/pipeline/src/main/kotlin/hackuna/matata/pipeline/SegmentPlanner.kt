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
        return segments
            .filter { it.endMs - it.startMs >= config.minUtteranceMs }
            .sortedBy { it.startMs }
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
