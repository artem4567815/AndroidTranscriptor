package hackuna.matata.ml.diarization

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeakerSegment
import org.junit.Assert.assertEquals
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
}
