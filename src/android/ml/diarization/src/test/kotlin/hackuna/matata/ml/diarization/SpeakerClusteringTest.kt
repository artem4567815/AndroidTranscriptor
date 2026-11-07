package hackuna.matata.ml.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeakerClusteringTest {
    private val rnd = Random(42)

    /** Отпечаток «голоса» [center] с шумом. */
    private fun voice(center: FloatArray, noise: Float = 0.15f) =
        FloatArray(center.size) { center[it] + (rnd.nextFloat() - 0.5f) * 2 * noise }

    private fun center(seed: Int) = Random(seed).let { r -> FloatArray(32) { r.nextFloat() - 0.5f } }

    @Test
    fun `два голоса вперемешку — два спикера, чередование угадано`() {
        val a = center(1)
        val b = center(2)
        val units = List(14) { if (it % 2 == 0) voice(a) else voice(b) }
        val labels = SpeakerClustering.cluster(units, numSpeakers = 0)
        assertEquals(List(14) { it % 2 }, labels.toList())
    }

    @Test
    fun `три голоса — три спикера`() {
        val cs = listOf(center(1), center(2), center(3))
        val order = listOf(0, 1, 2, 0, 1, 2, 2, 1, 0, 0)
        val labels = SpeakerClustering.cluster(order.map { voice(cs[it]) }, numSpeakers = 0)
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 2, 1, 0, 0), labels.toList())
    }

    @Test
    fun `один голос — один спикер`() {
        val a = center(1)
        val labels = SpeakerClustering.cluster(List(8) { voice(a, 0.05f) }, numSpeakers = 0)
        assertTrue(labels.all { it == 0 })
    }

    @Test
    fun `заданное число спикеров соблюдается`() {
        val a = center(1)
        val labels = SpeakerClustering.cluster(List(6) { voice(a) }, numSpeakers = 2)
        assertEquals(2, labels.distinct().size)
    }

    @Test
    fun `участок без отпечатка получает метку соседа, лишние — ближайший центр`() {
        val a = center(1)
        val b = center(2)
        val units: List<FloatArray?> = listOf(voice(a), voice(b), null, voice(b), voice(a), voice(a), voice(b))
        val labels = SpeakerClustering.cluster(units, numSpeakers = 0, maxUnits = 4)
        assertEquals(listOf(0, 1, 1, 1, 0, 0, 1), labels.toList())
    }

    @Test
    fun `пустой вход и один участок`() {
        assertEquals(0, SpeakerClustering.cluster(emptyList(), 0).size)
        assertEquals(listOf(0), SpeakerClustering.cluster(listOf(center(1)), 0).toList())
    }
}
