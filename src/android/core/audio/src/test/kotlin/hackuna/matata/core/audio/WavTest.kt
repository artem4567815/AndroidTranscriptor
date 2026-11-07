package hackuna.matata.core.audio

import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.StenoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test
    fun `запись и чтение WAV 16 кГц моно без потерь сверх квантования`() {
        val file = File.createTempFile("test", ".wav")
        val samples = FloatArray(1600) { ((it % 100) - 50) / 100f }
        WavWriter.write(file, PcmAudio(samples))

        assertEquals(44 + 1600 * 2L, file.length())
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, bb.getShort(22).toInt()) // моно
        assertEquals(16_000, bb.getInt(24))
        assertEquals(16, bb.getShort(34).toInt())

        val back = WavReader.read(file)
        assertEquals(16_000, back.sampleRate)
        assertEquals(100L, back.durationMs)
        assertArrayEquals(samples, back.samples, 1f / 16_000)
        file.delete()
    }

    @Test
    fun `потоковый writer дописывает длительность`() {
        val file = File.createTempFile("stream", ".wav")
        val w = WavWriter(file)
        repeat(10) { w.writePcm16(ShortArray(1600)) }
        w.close()
        assertEquals(1000L, w.durationMs)
        assertEquals(16_000 * 2, ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        file.delete()
    }

    @Test
    fun `стерео 44,1 кГц приводится к 16 кГц моно`() {
        val file = File.createTempFile("stereo", ".wav")
        val frames = 44_100
        val bb = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(WavWriter.header(frames * 4L, 44_100, 2))
        repeat(frames) { bb.putShort(16384); bb.putShort(0) }
        file.writeBytes(bb.array())

        val audio = WavReader.readAs16kMono(file)
        assertEquals(16_000, audio.sampleRate)
        assertEquals(1000L, audio.durationMs)
        assertEquals(0.25f, audio.samples[8000], 0.01f)
        file.delete()
    }

    @Test
    fun `мусор вместо WAV — DECODE_FAILED`() {
        assertThrows(StenoException::class.java) { WavReader.read(ByteArray(100)) }
    }
}
