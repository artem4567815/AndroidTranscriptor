package hackuna.matata.core.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Resampler {
    /** Пересчёт частоты целиком (WAV, тесты). Тот же фильтр, что у потокового [StreamingResampler]. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "Частоты должны быть положительными" }
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val r = StreamingResampler(fromRate, toRate, input.size.toLong() * toRate / fromRate + 1)
        for (x in input) r.add(x)
        return r.finish()
    }
}

/**
 * Потоковый пересчёт частоты: оконный sinc (Ханн, 16 переходов через ноль), срез 0,97 от нового
 * Найквиста — частоты выше него не «заворачиваются» в полосу речи. Это важно для отпечатков голоса:
 * линейная интерполяция без фильтра при 48 → 16 кГц искажала тембр и портила диаризацию (docs/TESTS.md).
 *
 * Отсчёты подаются по одному ([add]), в памяти — только окно фильтра, а не весь исходник:
 * для длинных записей это сотни мегабайт экономии. Веса фильтра считаются заранее для каждой
 * фазы (48 → 16 кГц — 1 фаза, 44,1 → 16 кГц — 160).
 */
class StreamingResampler(
    private val fromRate: Int,
    private val toRate: Int,
    expectedOutput: Long = 0,
) {
    private val passthrough = fromRate == toRate
    private val up: Long // L: выход = вход * L / M
    private val down: Long // M
    private val cutoff: Double
    private val halfWidth: Double
    private val phaseWeights: Array<FloatArray>? // нормированные веса для каждой фазы
    private val phaseFirstTap: IntArray? // смещение первого веса относительно floor(center)
    private val minTap: Int

    private val output = FloatBuffer(expectedOutput.coerceIn(1L shl 12, Int.MAX_VALUE.toLong() / 2).toInt())

    // Окно входа: hist[0] — отсчёт с абсолютным номером histStart.
    private var hist = FloatArray(4096)
    private var histStart = 0L
    private var histLen = 0
    private var inputCount = 0L
    private var nextOut = 0L

    init {
        require(fromRate > 0 && toRate > 0) { "Частоты должны быть положительными" }
        val g = gcd(fromRate, toRate)
        up = (toRate / g).toLong()
        down = (fromRate / g).toLong()
        cutoff = min(1.0, toRate.toDouble() / fromRate) * 0.97
        halfWidth = ZERO_CROSSINGS / cutoff
        minTap = -ceil(halfWidth).toInt() - 1
        if (!passthrough && up <= MAX_PHASES) {
            phaseWeights = Array(up.toInt()) { p -> weights(p.toDouble() / up).second }
            phaseFirstTap = IntArray(up.toInt()) { p -> weights(p.toDouble() / up).first }
        } else {
            phaseWeights = null
            phaseFirstTap = null
        }
    }

    /** Сколько входных отсчётов держится в памяти (для теста: окно, а не вся запись). */
    internal val bufferedInputCapacity: Int get() = hist.size

    fun add(sample: Float) {
        if (passthrough) {
            output.add(sample)
            return
        }
        if (histLen == hist.size) compact()
        hist[histLen++] = sample
        inputCount++
        emitReady(final = false)
    }

    /** Досчитывает хвост (окно у конца обрезается и перенормируется) и отдаёт результат. */
    fun finish(): FloatArray {
        if (!passthrough) emitReady(final = true)
        return output.toArray()
    }

    private fun emitReady(final: Boolean) {
        val total = if (final) (inputCount * up / down).coerceAtLeast(minOf(inputCount, 1L)) else Long.MAX_VALUE
        while (nextOut < total) {
            val num = nextOut * down
            val base = num / up // floor(center)
            val phase = (num % up).toInt()
            val lastTap = base + ceil(halfWidth).toLong() + 1
            if (!final && lastTap >= inputCount) return
            val firstTap = base + minTap
            val v = if (phaseWeights != null && firstTap >= 0 && lastTap < inputCount) {
                val w = phaseWeights[phase]
                val start = (base + phaseFirstTap!![phase] - histStart).toInt()
                var acc = 0.0
                for (k in w.indices) acc += hist[start + k] * w[k]
                acc.toFloat()
            } else {
                direct(base + phase.toDouble() / up)
            }
            output.add(v)
            nextOut++
        }
    }

    /** Окно у краёв записи (или необычное отношение частот): веса считаются на месте и перенормируются. */
    private fun direct(center: Double): Float {
        val from = max(0L, floor(center - halfWidth).toLong())
        val to = min(inputCount - 1, ceil(center + halfWidth).toLong())
        var acc = 0.0
        var norm = 0.0
        for (j in from..to) {
            val w = kernel(j - center)
            acc += hist[(j - histStart).toInt()] * w
            norm += w
        }
        return if (norm > 1e-9) (acc / norm).toFloat() else 0f
    }

    /** Нормированные веса для дробной части центра [frac]: (смещение первого веса, веса). */
    private fun weights(frac: Double): Pair<Int, FloatArray> {
        val first = floor(frac - halfWidth).toInt()
        val last = ceil(frac + halfWidth).toInt()
        val raw = DoubleArray(last - first + 1) { kernel(first + it - frac) }
        val norm = raw.sum()
        return first to FloatArray(raw.size) { (raw[it] / norm).toFloat() }
    }

    /** Выкидывает отсчёты, которые уже не попадут ни в одно окно. */
    private fun compact() {
        val keepFrom = max(histStart, nextOut * down / up + minTap - 1)
        val drop = (keepFrom - histStart).toInt()
        if (drop > 0) {
            System.arraycopy(hist, drop, hist, 0, histLen - drop)
            histLen -= drop
            histStart = keepFrom
        }
        if (histLen == hist.size) hist = hist.copyOf(hist.size * 2)
    }

    private fun kernel(x: Double): Double {
        if (x <= -halfWidth || x >= halfWidth) return 0.0
        val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
        val window = 0.5 * (1 + cos(PI * x / halfWidth))
        return sinc * window
    }

    private companion object {
        const val ZERO_CROSSINGS = 16
        const val MAX_PHASES = 4096L

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

fun downmixToMono(interleaved: FloatArray, channels: Int): FloatArray {
    require(channels >= 1)
    if (channels == 1) return interleaved
    val frames = interleaved.size / channels
    return FloatArray(frames) { f ->
        var s = 0f
        for (c in 0 until channels) s += interleaved[f * channels + c]
        s / channels
    }
}
class FloatBuffer(initialCapacity: Int = 1 shl 16) {
    private var data = FloatArray(initialCapacity)
    var size: Int = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
}
