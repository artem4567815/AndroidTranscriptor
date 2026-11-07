package hackuna.matata.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import android.util.Log
import hackuna.matata.core.model.AudioDecoder
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.StenoException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Декодер на MediaExtractor + MediaCodec: mp3, m4a/aac, ogg (vorbis/opus), flac, wav, amr и всё,
 * что умеет платформа. Результат — PCM 16 кГц моно float. Без FFmpeg.
 *
 * [uri] — `content://...`, `file://...` или абсолютный путь.
 */
class MediaCodecAudioDecoder(private val context: Context) : AudioDecoder {

    override suspend fun decode(uri: String): PcmAudio = withContext(Dispatchers.Default) {
        // WAV читаем сами: быстрее и без сюрпризов с 24/32-битным PCM.
        localFile(uri)?.takeIf { it.extension.equals("wav", true) }?.let { file ->
            runCatching { return@withContext WavReader.readAs16kMono(file) }
                .onFailure { Log.w(TAG, "WAV не прочитан напрямую, пробуем MediaCodec", it) }
        }
        try {
            decodeWithMediaCodec(uri)
        } catch (e: StenoException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw StenoException(ErrorCode.OUT_OF_MEMORY, "Не хватило памяти при декодировании", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StenoException(ErrorCode.DECODE_FAILED, "Не удалось декодировать $uri: ${e.message}", e)
        }
    }

    private fun localFile(uri: String): File? = when {
        uri.startsWith("/") -> File(uri)
        uri.startsWith("file://") -> uri.toUri().path?.let(::File)
        else -> null
    }

    private suspend fun decodeWithMediaCodec(uri: String): PcmAudio {
        val extractor = MediaExtractor()
        val file = localFile(uri)
        if (file != null) extractor.setDataSource(file.absolutePath)
        else extractor.setDataSource(context, uri.toUri(), null)

        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw StenoException(ErrorCode.DECODE_FAILED, "В файле нет аудиодорожки")
        }
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val estimatedSamples = inputFormat.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
            ?.getLong(MediaFormat.KEY_DURATION)
            ?.let { (it * PcmAudio.MODEL_SAMPLE_RATE / 1_000_000L).toInt() }
            ?.takeIf { it > 0 }
        var resampler = StreamingResampler(sampleRate, PcmAudio.MODEL_SAMPLE_RATE, estimatedSamples?.toLong() ?: 0)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        resampler = StreamingResampler(sampleRate, PcmAudio.MODEL_SAMPLE_RATE, estimatedSamples?.toLong() ?: 0)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex >= 0 -> {
                        val out = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        appendMono(out, channels, pcmEncoding, resampler)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        val samples = resampler.finish()
        if (samples.isEmpty()) throw StenoException(ErrorCode.DECODE_FAILED, "Пустая аудиодорожка")
        Log.i(TAG, "decoded $mime ${sampleRate}Hz ch=$channels frames=${samples.size}")
        return PcmAudio(samples, PcmAudio.MODEL_SAMPLE_RATE)
    }

    private fun appendMono(
        buf: java.nio.ByteBuffer,
        channels: Int,
        encoding: Int,
        out: StreamingResampler,
    ) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                while (fb.remaining() >= channels) {
                    var s = 0f
                    repeat(channels) { s += fb.get() }
                    out.add(s / channels)
                }
            }
            else -> {
                val sb = buf.asShortBuffer()
                while (sb.remaining() >= channels) {
                    var s = 0f
                    repeat(channels) { s += sb.get() / 32768f }
                    out.add(s / channels)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AudioDecoder"
        const val TIMEOUT_US = 10_000L
    }
}

/** Длительность файла без полного декодирования (для списка записей). */
fun probeDurationMs(context: Context, uri: String): Long {
    val extractor = MediaExtractor()
    return try {
        if (uri.startsWith("/")) extractor.setDataSource(uri) else extractor.setDataSource(context, uri.toUri(), null)
        (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
            ?.getLong(MediaFormat.KEY_DURATION)?.div(1000) ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        extractor.release()
    }
}
