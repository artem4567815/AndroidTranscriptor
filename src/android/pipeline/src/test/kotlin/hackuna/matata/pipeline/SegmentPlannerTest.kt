package hackuna.matata.pipeline

import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeakerSegment
import hackuna.matata.core.model.SpeechRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {
    private val cfg = PipelineConfig(maxSegmentMs = 20_000, segmentPaddingMs = 150, minUtteranceMs = 200)

    @Test
    fun `паддинг в пределах записи и фильтр коротких`() {
        val chunks = SegmentPlanner.plan(
            listOf(SpeakerSegment(0, 100, 3000), SpeakerSegment(1, 3100, 3250), SpeakerSegment(1, 4000, 9950)),
            emptyList(), cfg, durationMs = 10_000,
        )
        assertEquals(2, chunks.size)
        assertEquals(AsrChunk(0, 100, 3000, 0, 3150), chunks[0])
        assertEquals(AsrChunk(1, 4000, 9950, 3850, 10_000), chunks[1])
    }

    @Test
    fun `длинный сегмент режется по паузам VAD`() {
        val speech = listOf(SpeechRegion(0, 9000), SpeechRegion(10_000, 18_000), SpeechRegion(19_000, 35_000), SpeechRegion(36_000, 45_000))
        val chunks = SegmentPlanner.plan(listOf(SpeakerSegment(0, 0, 45_000)), speech, cfg, 45_000)
        assertEquals(listOf(0L to 18_500L, 18_500L to 35_500L, 35_500L to 45_000L), chunks.map { it.startMs to it.endMs })
        assertTrue(chunks.all { it.endMs - it.startMs <= cfg.maxSegmentMs })
    }

    @Test
    fun `без пауз — жёсткая нарезка по maxSegmentMs`() {
        val parts = SegmentPlanner.split(SpeakerSegment(2, 0, 50_000), emptyList(), 20_000)
        assertEquals(listOf(0L to 20_000L, 20_000L to 40_000L, 40_000L to 50_000L), parts.map { it.startMs to it.endMs })
        assertTrue(parts.all { it.speaker == 2 })
    }

    @Test
    fun `fallback — один спикер на всю речь`() {
        assertEquals(listOf(SpeakerSegment(0, 1, 2)), SegmentPlanner.fallbackSegments(listOf(SpeechRegion(1, 2))))
    }

    @Test
    fun `частичное пересечение разных спикеров режется посередине`() {
        val out = SegmentPlanner.resolveOverlaps(listOf(SpeakerSegment(0, 0, 5000), SpeakerSegment(1, 4000, 9000)))
        assertEquals(listOf(SpeakerSegment(0, 0, 4500), SpeakerSegment(1, 4500, 9000)), out)
    }

    @Test
    fun `короткая реплика внутри длинной разрезает длинную`() {
        val out = SegmentPlanner.resolveOverlaps(listOf(SpeakerSegment(0, 0, 10_000), SpeakerSegment(1, 4000, 5000)))
        assertEquals(
            listOf(SpeakerSegment(0, 0, 4000), SpeakerSegment(1, 4000, 5000), SpeakerSegment(0, 5000, 10_000)),
            out,
        )
    }

    @Test
    fun `пересечение одного спикера сливается`() {
        val out = SegmentPlanner.resolveOverlaps(listOf(SpeakerSegment(2, 0, 3000), SpeakerSegment(2, 2000, 6000)))
        assertEquals(listOf(SpeakerSegment(2, 0, 6000)), out)
    }

    @Test
    fun `после разрешения ни один момент не принадлежит двум спикерам`() {
        val input = listOf(
            SpeakerSegment(0, 0, 8000), SpeakerSegment(1, 1000, 3000), SpeakerSegment(2, 2500, 12_000),
            SpeakerSegment(0, 11_000, 15_000), SpeakerSegment(1, 14_000, 14_500),
        )
        val out = SegmentPlanner.resolveOverlaps(input)
        assertTrue(out.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
        assertTrue(out.all { it.endMs > it.startMs })
        // Вся исходная речь покрыта: от 0 до 15 с без дыр.
        assertEquals(0L, out.first().startMs)
        assertEquals(15_000L, out.last().endMs)
        assertTrue(out.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
    }

    @Test
    fun `пересекающиеся сегменты не уходят в ASR дважды`() {
        val chunks = SegmentPlanner.plan(
            listOf(SpeakerSegment(0, 0, 5000), SpeakerSegment(1, 4000, 9000)),
            emptyList(), cfg.copy(segmentPaddingMs = 0), durationMs = 9000,
        )
        assertEquals(listOf(0L to 4500L, 4500L to 9000L), chunks.map { it.startMs to it.endMs })
    }

    @Test
    fun `паддинг не залезает в соседний кусок — слово на стыке не дублируется`() {
        val chunks = SegmentPlanner.plan(
            listOf(SpeakerSegment(0, 1000, 5000), SpeakerSegment(1, 5000, 9000), SpeakerSegment(0, 9100, 12_000)),
            emptyList(), cfg, durationMs = 20_000,
        )
        // стык 5000: без захода в соседа; пауза 100 мс между 9000 и 9100 — паддинг до соседа, не больше.
        assertEquals(listOf(850L to 5000L, 5000L to 9100L, 9000L to 12_150L), chunks.map { it.audioStartMs to it.audioEndMs })
    }

    @Test
    fun `ничья речь рядом с отрезком расширяет его`() {
        // Диаризация оборвала слово на 10 000, а VAD слышит речь до 10 400.
        val out = SegmentPlanner.coverSpeech(
            listOf(SpeakerSegment(0, 3000, 10_000), SpeakerSegment(1, 11_000, 15_000)),
            listOf(SpeechRegion(3000, 10_400), SpeechRegion(11_000, 15_000)),
        )
        assertEquals(listOf(SpeakerSegment(0, 3000, 10_400), SpeakerSegment(1, 11_000, 15_000)), out)
    }

    @Test
    fun `далёкая ничья речь становится отрезком ближайшего спикера`() {
        val out = SegmentPlanner.coverSpeech(
            listOf(SpeakerSegment(0, 0, 2000), SpeakerSegment(1, 10_000, 12_000)),
            listOf(SpeechRegion(0, 2000), SpeechRegion(7000, 8500), SpeechRegion(10_000, 12_000)),
        )
        assertEquals(
            listOf(SpeakerSegment(0, 0, 2000), SpeakerSegment(1, 7000, 8500), SpeakerSegment(1, 10_000, 12_000)),
            out,
        )
    }

    @Test
    fun `покрытая речь не меняется, отрезки не пересекаются`() {
        val segs = listOf(SpeakerSegment(0, 0, 5000), SpeakerSegment(1, 5000, 9000))
        assertEquals(segs, SegmentPlanner.coverSpeech(segs, listOf(SpeechRegion(100, 8900))))
        val out = SegmentPlanner.coverSpeech(
            listOf(SpeakerSegment(0, 1000, 2000), SpeakerSegment(1, 2600, 4000)),
            listOf(SpeechRegion(500, 4500)),
        )
        assertTrue(out.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
        assertEquals(500L, out.first().startMs)
        assertEquals(4500L, out.last().endMs)
    }

    @Test
    fun `участки речи — VAD, разрезанный по смене спикера, длинные — окнами`() {
        val speech = listOf(SpeechRegion(0, 800), SpeechRegion(1500, 6000), SpeechRegion(7000, 7600))
        // внутри 1500–6000 первый проход видит смену спикера на 3500: обе части ≥ 1 с — режем
        val segs = listOf(SpeakerSegment(0, 0, 800), SpeakerSegment(0, 1500, 3400), SpeakerSegment(1, 3600, 6000))
        val units = SegmentPlanner.speechUnits(speech, segs)
        assertEquals(
            listOf(0L to 800L, 1500L to 3500L, 3500L to 6000L, 7000L to 7600L),
            units.map { it.startMs to it.endMs },
        )
    }

    @Test
    fun `короткая смена спикера внутри участка не режет его`() {
        val units = SegmentPlanner.speechUnits(
            listOf(SpeechRegion(0, 1800)),
            listOf(SpeakerSegment(0, 0, 1200), SpeakerSegment(1, 1300, 1800)),
        )
        assertEquals(listOf(0L to 1800L), units.map { it.startMs to it.endMs })
    }

    @Test
    fun `монолог длиннее 3 с режется на окна для отпечатков`() {
        val units = SegmentPlanner.speechUnits(listOf(SpeechRegion(0, 5000)), listOf(SpeakerSegment(0, 0, 5000)))
        assertEquals(listOf(0L to 1500L, 1500L to 3000L, 3000L to 5000L), units.map { it.startMs to it.endMs })
    }

    @Test
    fun `метки участков из второго прохода или по перекрытию с первым`() {
        val units = listOf(SpeechRegion(0, 1000), SpeechRegion(2000, 3000), SpeechRegion(9000, 9500))
        val segs = listOf(SpeakerSegment(4, 0, 1200), SpeakerSegment(7, 1900, 3100))
        assertEquals(listOf(1, 0, 1), SegmentPlanner.labelUnits(units, listOf(1, 0, 1), segs).map { it.speaker })
        // без второго прохода: перекрытие, а для 9000–9500 — ближайший отрезок (7)
        assertEquals(listOf(4, 7, 7), SegmentPlanner.labelUnits(units, null, segs).map { it.speaker })
    }

    @Test
    fun `окна одного спикера снова склеиваются для ASR`() {
        val chunks = SegmentPlanner.plan(
            listOf(SpeakerSegment(0, 0, 1500), SpeakerSegment(0, 1500, 3000), SpeakerSegment(1, 3000, 5000)),
            listOf(SpeechRegion(0, 5000)), cfg.copy(segmentPaddingMs = 0), durationMs = 5000,
        )
        assertEquals(listOf(0L to 3000L, 3000L to 5000L), chunks.map { it.startMs to it.endMs })
    }
}
