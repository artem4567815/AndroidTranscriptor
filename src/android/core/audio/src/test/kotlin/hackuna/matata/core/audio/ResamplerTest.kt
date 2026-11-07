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

    /** Прежняя реализация «целиком» (до потоковой) — эталон: потоковая должна давать то же самое. */
    private fun reference(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        val ratio = toRate.toDouble() / fromRate
        val out = FloatArray(kotlin.math.floor(input.size * ratio).toInt().coerceAtLeast(1))
        val cutoff = kotlin.math.min(1.0, ratio) * 0.97
        val hw = 16 / cutoff
        for (i in out.indices) {
            val center = i / ratio
            var acc = 0.0
            var norm = 0.0
            for (j in kotlin.math.max(0, (center - hw).toInt())..kotlin.math.min(input.size - 1, (center + hw).toInt() + 1)) {
                val x = j - center
                if (x <= -hw || x >= hw) continue
                val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
                val w = sinc * 0.5 * (1 + kotlin.math.cos(PI * x / hw))
                acc += input[j] * w
                norm += w
            }
            out[i] = if (norm > 1e-9) (acc / norm).toFloat() else 0f
        }
        return out
    }

    @Test
    fun `потоковый пересчёт совпадает с прежним sinc-фильтром`() {
        val rnd = java.util.Random(1)
        for (from in listOf(44_100, 48_000, 22_050, 8_000, 32_000)) {
            val input = FloatArray(from / 2 + 37) { (rnd.nextGaussian() * 0.3).toFloat() }
            val expected = reference(input, from, 16_000)
            val actual = Resampler.resample(input, from, 16_000)
            assertEquals("длина при $from Гц", expected.size, actual.size)
            var maxErr = 0f
            for (i in expected.indices) maxErr = maxOf(maxErr, abs(expected[i] - actual[i]))
            assertTrue("$from Гц: maxErr=$maxErr", maxErr < 1e-4f)
        }
    }

    @Test
    fun `потоковый пересчёт держит в памяти только окно`() {
        val r = StreamingResampler(48_000, 16_000)
        repeat(48_000 * 60) { r.add(if (it % 2 == 0) 0.1f else -0.1f) } // минута
        assertTrue("буфер ${r.bufferedInputCapacity}", r.bufferedInputCapacity <= 8192)
        assertEquals(16_000 * 60, r.finish().size)
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
