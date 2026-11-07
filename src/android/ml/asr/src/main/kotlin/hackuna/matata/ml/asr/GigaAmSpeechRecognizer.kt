package hackuna.matata.ml.asr

import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.RecognizedSegment
import hackuna.matata.core.model.SpeechRecognizer
import hackuna.matata.core.model.StenoException
import hackuna.matata.ml.runtime.ModelManager
import hackuna.matata.ml.sherpa.SherpaRuntime

/** Идентификаторы файлов в assets/models/manifest.json. */
object AsrModelIds {
    const val ENCODER = "asr.encoder"
    const val DECODER = "asr.decoder"
    const val JOINER = "asr.joiner"
    const val TOKENS = "asr.tokens"
}

/**
 * ASR на GigaAM v3 e2e (транcдьюсер с пунктуацией, int8) через sherpa-onnx OfflineRecognizer.
 *
 * TODO(роль 2): реализовать по образцу sherpa-onnx/kotlin-api-examples:
 *  - OfflineRecognizerConfig(modelConfig = OfflineModelConfig(transducer = OfflineTransducerModelConfig(
 *      encoder, decoder, joiner), tokens = ..., numThreads = config.numThreads, modelType = "nemo_transducer"),
 *      decodingMethod = "greedy_search")
 *  - recognize(): createStream() → acceptWaveform(samples, sampleRate) → decode(stream) → getResult(stream)
 *  - вернуть RecognizedSegment с токенами и таймштампами (timestamps — в секундах от начала сегмента)
 *  - release() в close(); проверить RTF на 2–3 телефонах.
 */
class GigaAmSpeechRecognizer(
    private val models: ModelManager,
    private val config: PipelineConfig,
) : SpeechRecognizer {

    init {
        if (!SherpaRuntime.isAvailable()) {
            throw StenoException(ErrorCode.MODELS_MISSING, "Нет libsherpa-onnx-jni.so (scripts/fetch_sherpa.sh)")
        }
        // Проверяем пути заранее, чтобы ошибка была понятной, а не из JNI.
        listOf(AsrModelIds.ENCODER, AsrModelIds.DECODER, AsrModelIds.JOINER, AsrModelIds.TOKENS).forEach(models::require)
    }

    override fun recognize(samples: FloatArray, sampleRate: Int): RecognizedSegment {
        throw NotImplementedError("TODO(роль 2): распознавание GigaAM через sherpa-onnx OfflineRecognizer (threads=${config.numThreads})")
    }

    override fun close() {
        // TODO(роль 2): recognizer.release()
    }
}
