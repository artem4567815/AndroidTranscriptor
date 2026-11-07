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
 *  - речь, которую VAD слышит, а диаризация ни к кому не отнесла, отдаётся ближайшему спикеру;
 *  - пересечения отрезков разных спикеров разрешаются (см. [resolveOverlaps]), чтобы один и тот же
 *    кусок аудио не распознавался дважды и слова не дублировались в двух репликах;
 *  - к каждому куску добавляется паддинг segmentPaddingMs — только в тишину, не заходя в соседний кусок.
 */
object SegmentPlanner {

    fun plan(
        segments: List<SpeakerSegment>,
        speech: List<SpeechRegion>,
        config: PipelineConfig,
        durationMs: Long,
    ): List<AsrChunk> {
        val cuts = pauseMidpoints(speech)
        val parts = coverSpeech(resolveOverlaps(segments), speech)
            .filter { it.endMs - it.startMs >= config.minUtteranceMs }
            .flatMap { split(it, cuts, config.maxSegmentMs) }
        return parts.mapIndexed { i, s ->
            // Паддинг только в тишину: не залезаем в соседний кусок, иначе слово на стыке
            // распознаётся дважды («Дверь открыл» + «Дверь открывали»).
            val prevEnd = parts.getOrNull(i - 1)?.endMs ?: 0L
            val nextStart = parts.getOrNull(i + 1)?.startMs ?: durationMs
            AsrChunk(
                speaker = s.speaker,
                startMs = s.startMs,
                endMs = s.endMs,
                audioStartMs = maxOf(s.startMs - config.segmentPaddingMs, prevEnd, 0L).coerceAtMost(s.startMs),
                audioEndMs = minOf(s.endMs + config.segmentPaddingMs, nextStart, durationMs).coerceAtLeast(s.endMs),
            )
        }
    }

    /**
     * «Ничьей» речи не бывает: участки, где VAD слышит речь, а диаризация не отнесла их ни к кому,
     * отдаются ближайшему по времени спикеру. Иначе такие куски не попадают в ASR и слова теряются
     * (обрезанное «Револю…» вместо «революционер»).
     *  - кусок в пределах [attachMs] от соседнего отрезка — этот отрезок расширяется до него;
     *  - дальше — становится отдельным отрезком ближайшего спикера.
     * Вход — непересекающиеся отрезки (после [resolveOverlaps]), выход — тоже, отсортированный.
     */
    fun coverSpeech(
        segments: List<SpeakerSegment>,
        speech: List<SpeechRegion>,
        attachMs: Long = 300,
        minGapMs: Long = 50,
    ): List<SpeakerSegment> {
        if (segments.isEmpty() || speech.isEmpty()) return segments
        val out = segments.sortedBy { it.startMs }.toMutableList()
        for (gap in uncovered(speech, out).filter { it.endMs - it.startMs >= minGapMs }) {
            val prevIdx = out.indexOfLast { it.endMs <= gap.startMs }
            val nextIdx = out.indexOfFirst { it.startMs >= gap.endMs }
            val distPrev = if (prevIdx >= 0) gap.startMs - out[prevIdx].endMs else Long.MAX_VALUE
            val distNext = if (nextIdx >= 0) out[nextIdx].startMs - gap.endMs else Long.MAX_VALUE
            if (distPrev <= distNext) {
                val prev = out[prevIdx]
                if (distPrev <= attachMs) out[prevIdx] = prev.copy(endMs = gap.endMs)
                else out += SpeakerSegment(prev.speaker, gap.startMs, gap.endMs)
            } else {
                val next = out[nextIdx]
                if (distNext <= attachMs) out[nextIdx] = next.copy(startMs = gap.startMs)
                else out += SpeakerSegment(next.speaker, gap.startMs, gap.endMs)
            }
            out.sortBy { it.startMs }
        }
        return out
    }

    /** Части участков речи, не покрытые ни одним отрезком. */
    internal fun uncovered(speech: List<SpeechRegion>, segments: List<SpeakerSegment>): List<SpeechRegion> {
        val result = ArrayList<SpeechRegion>()
        for (region in speech.sortedBy { it.startMs }) {
            var cur = region.startMs
            for (seg in segments) {
                if (seg.endMs <= cur || seg.startMs >= region.endMs) continue
                if (seg.startMs > cur) result += SpeechRegion(cur, seg.startMs)
                cur = maxOf(cur, seg.endMs)
            }
            if (cur < region.endMs) result += SpeechRegion(cur, region.endMs)
        }
        return result
    }

    /**
     * Делает отрезки непересекающимися — каждый момент записи принадлежит одному спикеру:
     *  - тот же спикер, пересекаются или стыкуются — отрезки сливаются;
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
                prev != null && s.speaker == prev.speaker && s.startMs <= prev.endMs ->
                    out[out.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, s.endMs))
                prev == null || s.startMs >= prev.endMs -> out += s
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

    /** Участок длиннее — режется на окна для отпечатков голоса (для ASR окна одного спикера снова склеятся). */
    const val UNIT_MAX_MS = 3000L
    const val UNIT_WINDOW_MS = 1500L

    /** Смена спикера внутри участка речи учитывается, только если обе части не короче этого. */
    const val MIN_TURN_MS = 1000L

    /**
     * Участки речи для второго прохода диаризации и ASR:
     *  - основа — участки VAD (паузы между ними — естественные границы, слова не рвутся);
     *  - внутри участка режем там, где первый проход (sherpa) видит смену спикера, если обе части ≥ [MIN_TURN_MS];
     *  - длинные куски (> [UNIT_MAX_MS]) — на окна [UNIT_WINDOW_MS], чтобы отпечаток был у каждой части монолога.
     * Нет VAD-участков — берём отрезки первого прохода.
     */
    fun speechUnits(speech: List<SpeechRegion>, segments: List<SpeakerSegment>): List<SpeechRegion> {
        val resolved = resolveOverlaps(segments)
        val base = if (speech.isNotEmpty()) speech.sortedBy { it.startMs }
        else resolved.map { SpeechRegion(it.startMs, it.endMs) }
        return base.flatMap { region ->
            val inside = resolved.filter { it.endMs > region.startMs && it.startMs < region.endMs }
            val cuts = ArrayList<Long>()
            var last = region.startMs
            for ((a, b) in inside.zipWithNext()) {
                if (a.speaker == b.speaker) continue
                val cut = ((a.endMs + b.startMs) / 2).coerceIn(region.startMs, region.endMs)
                if (cut - last >= MIN_TURN_MS && region.endMs - cut >= MIN_TURN_MS) {
                    cuts += cut
                    last = cut
                }
            }
            val bounds = listOf(region.startMs) + cuts + region.endMs
            bounds.zipWithNext().flatMap { (a, b) -> windows(a, b) }
        }
    }

    private fun windows(a: Long, b: Long): List<SpeechRegion> {
        if (b - a <= UNIT_MAX_MS) return listOf(SpeechRegion(a, b))
        val out = ArrayList<SpeechRegion>()
        var t = a
        while (t < b) {
            var e = minOf(b, t + UNIT_WINDOW_MS)
            if (b - e < UNIT_WINDOW_MS / 2) e = b
            out += SpeechRegion(t, e)
            t = e
        }
        return out
    }

    /**
     * Спикер для каждого участка: из второго прохода ([labels]) или, если его нет, — спикер первого прохода
     * с наибольшим перекрытием (нет перекрытия — ближайший по времени; нет отрезков — спикер 0).
     */
    fun labelUnits(units: List<SpeechRegion>, labels: List<Int>?, segments: List<SpeakerSegment>): List<SpeakerSegment> {
        if (labels != null && labels.size == units.size) {
            return units.mapIndexed { i, u -> SpeakerSegment(labels[i], u.startMs, u.endMs) }
        }
        val resolved = resolveOverlaps(segments)
        return units.map { u ->
            val best = resolved.maxByOrNull { minOf(u.endMs, it.endMs) - maxOf(u.startMs, it.startMs) }
            val overlap = best?.let { minOf(u.endMs, it.endMs) - maxOf(u.startMs, it.startMs) } ?: 0
            val speaker = when {
                best != null && overlap > 0 -> best.speaker
                resolved.isNotEmpty() -> resolved.minBy { maxOf(it.startMs - u.endMs, u.startMs - it.endMs) }.speaker
                else -> 0
            }
            SpeakerSegment(speaker, u.startMs, u.endMs)
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
