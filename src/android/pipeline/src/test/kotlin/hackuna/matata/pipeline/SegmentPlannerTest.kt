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
}
