package hackuna.matata.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Resampler {
    private const val ZERO_CROSSINGS = 16

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "Частоты должны быть положительными" }
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val ratio = toRate.toDouble() / fromRate
        val outLen = floor(input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val cutoff = min(1.0, ratio) * 0.97
        val halfWidth = ZERO_CROSSINGS / cutoff // в отсчётах входа
        for (i in 0 until outLen) {
            val center = i / ratio
            val from = max(0, (center - halfWidth).toInt())
            val to = min(input.size - 1, (center + halfWidth).toInt() + 1)
            var acc = 0.0
            var norm = 0.0
            for (j in from..to) {
                val x = j - center
                val w = kernel(x, cutoff, halfWidth)
                acc += input[j] * w
                norm += w
            }
            out[i] = if (norm > 1e-9) (acc / norm).toFloat() else 0f
        }
        return out
    }

    private fun kernel(x: Double, cutoff: Double, halfWidth: Double): Double {
        if (x <= -halfWidth || x >= halfWidth) return 0.0
        val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
        val window = 0.5 * (1 + cos(PI * x / halfWidth))
        return sinc * window
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
