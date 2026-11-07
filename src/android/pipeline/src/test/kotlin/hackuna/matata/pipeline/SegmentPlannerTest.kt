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
}
