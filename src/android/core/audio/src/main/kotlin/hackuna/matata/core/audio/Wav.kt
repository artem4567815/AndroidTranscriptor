package hackuna.matata.core.audio

import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.StenoException
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriter(
    val file: File,
    val sampleRate: Int = PcmAudio.MODEL_SAMPLE_RATE,
    val channels: Int = 1,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(HEADER_SIZE))
    }
    private var dataBytes = 0L
    private var closed = false

    val durationMs: Long get() = dataBytes / 2 / channels * 1000 / sampleRate

    fun writePcm16(samples: ShortArray, count: Int = samples.size) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(samples[i])
        raf.write(bb.array())
        dataBytes += count * 2
    }

    fun writeFloat(samples: FloatArray) {
        val s = ShortArray(samples.size) { floatToPcm16(samples[it]) }
        writePcm16(s)
    }

    override fun close() {
        if (closed) return
        closed = true
        raf.seek(0)
        raf.write(header(dataBytes, sampleRate, channels))
        raf.close()
    }

    companion object {
        const val HEADER_SIZE = 44

        fun header(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
            val bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt((36 + dataBytes).toInt())
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16)
            bb.putShort(1)
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * channels * 2)
            bb.putShort((channels * 2).toShort())
            bb.putShort(16)
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataBytes.toInt())
            return bb.array()
        }

        fun write(file: File, audio: PcmAudio) {
            WavWriter(file, audio.sampleRate).use { it.writeFloat(audio.samples) }
        }
    }
}

fun floatToPcm16(v: Float): Short = (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()

object WavReader {
    fun read(file: File): PcmAudio = read(file.readBytes())

    fun readAs16kMono(file: File): PcmAudio {
        val raw = read(file)
        return if (raw.sampleRate == PcmAudio.MODEL_SAMPLE_RATE) raw
        else PcmAudio(Resampler.resample(raw.samples, raw.sampleRate, PcmAudio.MODEL_SAMPLE_RATE))
    }

    fun read(bytes: ByteArray): PcmAudio {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun fail(msg: String): Nothing = throw StenoException(ErrorCode.DECODE_FAILED, "WAV: $msg")
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) fail("нет заголовка RIFF/WAVE")

        var pos = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt() and 0xFFFF
                    channels = bb.getShort(body + 2).toInt()
                    sampleRate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                    if (format == 0xFFFE && size >= 26) format = bb.getShort(body + 24).toInt() // EXTENSIBLE
                }
                "data" -> {
                    dataOffset = body
                    dataSize = minOf(size.toLong() and 0xFFFFFFFFL, (bytes.size - body).toLong()).toInt()
                }
            }
            if (dataOffset >= 0 && format >= 0) break
            pos = body + size + (size and 1)
        }
        if (format < 0 || dataOffset < 0) fail("нет блоков fmt/data")
        if (channels < 1 || sampleRate <= 0) fail("неверные параметры")
        val bytesPerSample = bits / 8
        val frames = dataSize / (bytesPerSample * channels)
        val interleaved = FloatArray(frames * channels)
        for (i in interleaved.indices) {
            val o = dataOffset + i * bytesPerSample
            interleaved[i] = when {
                format == 3 && bits == 32 -> bb.getFloat(o)
                format == 1 && bits == 16 -> bb.getShort(o) / 32768f
                format == 1 && bits == 8 -> ((bytes[o].toInt() and 0xFF) - 128) / 128f
                format == 1 && bits == 24 -> {
                    val v = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                        (bytes[o + 2].toInt() shl 16)
                    v / 8388608f
                }
                format == 1 && bits == 32 -> bb.getInt(o) / 2147483648f
                else -> fail("формат $format/$bits бит не поддерживается")
            }
        }
        return PcmAudio(downmixToMono(interleaved, channels), sampleRate)
    }
}
