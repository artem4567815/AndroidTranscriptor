package hackuna.matata.ml.diarization

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeakerSegment
import hackuna.matata.core.model.SpeechRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiarizationMappingTest {

    private fun seg(start: Float, end: Float, speaker: Int) = OfflineSpeakerDiarizationSegment(start, end, speaker, -2f)

    @Test
    fun `секунды переводятся в миллисекунды с округлением`() {
        val out = DiarizationMapping.toSpeakerSegments(arrayOf(seg(1.2504f, 4.3799f, 0)))
        assertEquals(listOf(SpeakerSegment(0, 1250, 4380)), out)
    }

    @Test
    fun `сортировка по времени и выброс пустых отрезков`() {
        val out = DiarizationMapping.toSpeakerSegments(
            arrayOf(seg(5f, 7f, 1), seg(0f, 2f, 0), seg(3f, 3f, 2), seg(-0.01f, 0.5f, 2)),
        )
        assertEquals(
            listOf(SpeakerSegment(2, 0, 500), SpeakerSegment(0, 0, 2000), SpeakerSegment(1, 5000, 7000)),
            out,
        )
    }

    @Test
    fun `авто — порог, фиксированное число — кластеры`() {
        val auto = DiarizationMapping.clustering(PipelineConfig(numSpeakers = 0, clusterThreshold = 0.7f))
        assertEquals(-1, auto.numClusters)
        assertEquals(0.7f, auto.threshold)

        val fixed = DiarizationMapping.clustering(PipelineConfig(numSpeakers = 3))
        assertEquals(3, fixed.numClusters)
    }

    @Test
    fun `callback имеет JVM-сигнатуру, которую ищет JNI sherpa`() {
        val m = ProgressCallback {}.javaClass.getMethod(
            "invoke", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        )
        assertEquals(java.lang.Integer::class.java, m.returnType)

        var got = -1f
        ProgressCallback { got = it }.invoke(1, 4, 0)
        assertEquals(0.25f, got)
    }

    @Test
    fun `вырезки короткого участка не выходят за запись и не уже участка`() {
        val u = SpeechRegion(1000, 1600)
        val crops = DiarizationMapping.shortUnitCrops(u, from = 900, to = 1700, durationMs = 1750)
        assertEquals(
            listOf(900L to 1700L, 800L to 1700L, 900L to 1750L, 800L to 1750L, 1000L to 1600L),
            crops,
        )
        // У начала записи окно не уходит в минус.
        assertEquals(0L to 300L, DiarizationMapping.shortUnitCrops(SpeechRegion(0, 300), 0, 300, 5000)[1])
    }

    @Test
    fun `среднее отпечатков — по нормированным векторам`() {
        val avg = DiarizationMapping.average(listOf(floatArrayOf(3f, 0f), floatArrayOf(0f, 0.5f)))!!
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), avg, 1e-6f)
        assertNull(DiarizationMapping.average(emptyList()))
    }

    @Test
    fun `короткая запись — одно окно на весь файл`() {
        assertEquals(listOf(DiarizationWindow(0, 300_000, 0, 300_000)), DiarizationMapping.windows(300_000))
    }

    @Test
    fun `длинная запись — окна с перекрытием, сохраняемые части стыкуются без дыр`() {
        val w = DiarizationMapping.windows(24 * 60_000L, windowMs = 600_000, overlapMs = 30_000)
        assertEquals(3, w.size)
        assertEquals(DiarizationWindow(0, 600_000, 0, 585_000), w[0])
        assertEquals(DiarizationWindow(570_000, 1_170_000, 585_000, 1_155_000), w[1])
        assertEquals(DiarizationWindow(1_140_000, 1_440_000, 1_155_000, 1_440_000), w[2])
        w.zipWithNext().forEach { (a, b) -> assertEquals(a.keepToMs, b.keepFromMs) }
    }

    @Test
    fun `склейка — время записи, обрезка по перекрытию, свои номера спикеров у окна`() {
        val window = DiarizationWindow(570_000, 1_170_000, 585_000, 1_155_000)
        val out = DiarizationMapping.stitch(
            listOf(SpeakerSegment(0, 5_000, 20_000), SpeakerSegment(1, 30_000, 40_000), SpeakerSegment(0, 590_000, 600_000)),
            window,
            index = 1,
        )
        assertEquals(listOf(SpeakerSegment(1000, 585_000, 590_000), SpeakerSegment(1001, 600_000, 610_000)), out)
    }
}
