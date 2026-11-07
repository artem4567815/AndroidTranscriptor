package hackuna.matata.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun sine(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i].toDouble()
        return sqrt(s / (to - from))
    }

    @Test
    fun `длина выхода по отношению частот`() {
        assertEquals(16_000, Resampler.resample(FloatArray(44_100), 44_100, 16_000).size)
        assertEquals(16_000, Resampler.resample(FloatArray(48_000), 48_000, 16_000).size)
        assertEquals(16_000, Resampler.resample(FloatArray(8_000), 8_000, 16_000).size)
    }

    @Test
    fun `тон в полосе сохраняет амплитуду и частоту`() {
        val out = Resampler.resample(sine(440.0, 44_100, 1.0), 44_100, 16_000)
        val expected = sine(440.0, 16_000, 1.0)
        // Сравниваем середину, чтобы не учитывать краевые эффекты фильтра.
        var maxErr = 0f
        for (i in 1000 until 15_000) maxErr = maxOf(maxErr, abs(out[i] - expected[i]))
        assertTrue("maxErr=$maxErr", maxErr < 0.02f)
    }

    @Test
    fun `частоты выше нового Найквиста подавляются`() {
        // 12 кГц при 48 кГц после перевода в 16 кГц должен почти исчезнуть (Найквист 8 кГц).
        val out = Resampler.resample(sine(12_000.0, 48_000, 0.5), 48_000, 16_000)
        assertTrue("rms=${rms(out, 500, out.size - 500)}", rms(out, 500, out.size - 500) < 0.02)
    }

    @Test
    fun `та же частота — копия`() {
        val a = floatArrayOf(0.1f, 0.2f)
        val b = Resampler.resample(a, 16_000, 16_000)
        assertTrue(a.contentEquals(b) && a !== b)
    }

    @Test
    fun `даунмикс в моно`() {
        val mono = downmixToMono(floatArrayOf(1f, 0f, 0.5f, 0.5f), 2)
        assertTrue(mono.contentEquals(floatArrayOf(0.5f, 0.5f)))
    }

    @Test
    fun `предобработка убирает DC и нормализует пик`() {
        val input = FloatArray(16_000) { 0.3f + 0.05f * sin(2 * PI * 300 * it / 16_000).toFloat() }
        val out = DefaultAudioPreprocessor().process(hackuna.matata.core.model.PcmAudio(input)).samples
        val mean = out.drop(4000).average()
        assertTrue("mean=$mean", abs(mean) < 0.02)
        assertTrue(out.maxOf { abs(it) } <= 1f)
    }
}
