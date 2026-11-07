package hackuna.matata.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.StenoException
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Запись с микрофона через AudioRecord в WAV 16 кГц моно, 16 бит.
 * Чтение идёт в отдельном потоке; [onLevel] получает RMS-уровень 0..1 примерно 10 раз в секунду.
 * Разрешение RECORD_AUDIO проверяет вызывающий код (StenoPlugin).
 */
class AudioRecorder(private val sampleRate: Int = PcmAudio.MODEL_SAMPLE_RATE) {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var writer: WavWriter? = null

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(output: File, onLevel: (Float) -> Unit) {
        check(!running) { "Запись уже идёт" }
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw StenoException(ErrorCode.UNKNOWN, "AudioRecord не поддерживает $sampleRate Гц")
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate / 5 * 2),
            )
        } catch (e: SecurityException) {
            throw StenoException(ErrorCode.NO_PERMISSION, "Нет разрешения на микрофон", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw StenoException(ErrorCode.NO_PERMISSION, "Микрофон недоступен")
        }
        val w = WavWriter(output, sampleRate)
        writer = w
        running = true
        record.startRecording()
        worker = thread(name = "AudioRecorder") {
            val chunk = ShortArray(sampleRate / 10) // 100 мс
            try {
                while (running) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    w.writePcm16(chunk, n)
                    var sum = 0.0
                    for (i in 0 until n) sum += chunk[i] * chunk[i].toDouble()
                    val rms = sqrt(sum / n) / Short.MAX_VALUE
                    onLevel((rms * 4).coerceIn(0.0, 1.0).toFloat())
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    /** Останавливает запись, дописывает заголовок WAV и возвращает длительность в мс. */
    fun stop(): Long {
        running = false
        worker?.join(2000)
        worker = null
        val w = writer ?: return 0
        writer = null
        w.close()
        return w.durationMs
    }
}
