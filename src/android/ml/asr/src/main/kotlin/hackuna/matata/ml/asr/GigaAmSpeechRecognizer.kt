package hackuna.matata.ml.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.RecognizedSegment
import hackuna.matata.core.model.SpeechRecognizer
import hackuna.matata.core.model.StenoException
import hackuna.matata.core.model.TimedToken
import hackuna.matata.ml.runtime.ModelManager
import hackuna.matata.ml.sherpa.SherpaRuntime

/** Идентификаторы файлов в assets/models/manifest.json. */
object AsrModelIds {
    const val ENCODER = "asr.encoder"
    const val DECODER = "asr.decoder"
    const val JOINER = "asr.joiner"
    const val TOKENS = "asr.tokens"
}

/** ASR на GigaAM v3 e2e (трансдьюсер с пунктуацией, int8) через sherpa-onnx OfflineRecognizer. */
class GigaAmSpeechRecognizer(
    private val models: ModelManager,
    private val config: PipelineConfig,
) : SpeechRecognizer {

    private val recognizer: OfflineRecognizer

    init {
        if (!SherpaRuntime.isAvailable()) {
            throw StenoException(ErrorCode.MODELS_MISSING, "Нет libsherpa-onnx-jni.so (scripts/fetch_sherpa.sh)")
        }
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = models.require(AsrModelIds.ENCODER),
                        decoder = models.require(AsrModelIds.DECODER),
                        joiner = models.require(AsrModelIds.JOINER),
                    ),
                    tokens = models.require(AsrModelIds.TOKENS),
                    numThreads = config.numThreads,
                    modelType = "nemo_transducer",
                ),
                decodingMethod = "greedy_search",
            ),
        )
    }

    override fun recognize(samples: FloatArray, sampleRate: Int): RecognizedSegment {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val tokens = result.tokens.zip(result.timestamps.toList()) { token, seconds ->
                TimedToken(text = token, startMs = (seconds * 1000).toLong())
            }

            // Здесь доступна только позиция относительно начала переданного фрагмента.
            return RecognizedSegment(
                startMs = 0L,
                endMs = samples.size * 1000L / sampleRate,
                text = result.text,
                tokens = tokens,
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }
}
