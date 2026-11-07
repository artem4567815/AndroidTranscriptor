package hackuna.matata.ml.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.StenoException
import hackuna.matata.ml.runtime.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LlmModelIds {
    const val MODEL = "llm"
}

/**
 * Локальная LLM через LiteRT-LM (Kotlin API, без своего C++), модель Qwen3 `.litertlm` (docs/MODELS.md).
 *
 * Как устроено:
 *  - модель загружается при первом запросе и живёт до [close] — пайплайн закрывает движок сразу после
 *    этапа LLM, чтобы освободить память;
 *  - переупакованные веса кэшируются на диске ([cacheDir]) — это вдвое снижает пик ОЗУ;
 *  - на каждый запрос — отдельный диалог (Conversation): запросы не видят друг друга;
 *  - «размышления» Qwen3 выключены: иначе модель долго «думает вслух» перед ответом (минуты на CPU);
 *  - отмена обработки останавливает генерацию в нативном коде ([com.google.ai.edge.litertlm.Conversation.cancelProcess]).
 */
class LiteRtLmEngine(
    private val models: ModelManager,
    private val config: PipelineConfig,
    /**
     * Куда LiteRT кладёт переупакованные под процессор веса (.xnnpack_cache, ≈ 0,95 ГБ на диске).
     * Кэш обязателен: с ним веса читаются из файла (mmap) и система может их вытеснить, без него
     * они целиком лежат в ОЗУ — пик памяти 4,6 ГБ против 2,1 ГБ (замер в docs/TESTS.md), и телефон
     * убивает приложение. Бонус — повторная загрузка модели быстрее (Mac: 3,7 с против 7,8 с).
     */
    private val cacheDir: String,
) : LlmEngine {

    private var engine: Engine? = null

    init {
        if (models.path(LlmModelIds.MODEL) == null) {
            throw StenoException(ErrorCode.MODELS_MISSING, "LLM-модель не установлена")
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int, system: String?): String = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Текст запроса пустой" }
        require(maxTokens > 0) { "maxTokens должен быть больше нуля" }

        val current = engine ?: Engine(
            EngineConfig(
                modelPath = models.require(LlmModelIds.MODEL),
                backend = Backend.CPU(threadCount = config.numThreads),
                maxNumTokens = CONTEXT_TOKENS,
                cacheDir = cacheDir,
            ),
        ).also {
            it.initialize()
            engine = it
        }

        current.createConversation(
            ConversationConfig(
                systemInstruction = system?.let { Contents.of(it) },
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.3, seed = 42),
                maxOutputToken = maxTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            ),
        ).use { conversation ->
            val result = StringBuilder()
            try {
                conversation.sendMessageAsync(prompt).collect { result.append(textOf(it)) }
            } catch (e: CancellationException) {
                conversation.cancelProcess() // иначе генерация досчитается в фоне
                throw e
            }
            result.toString().trim()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        /** Контекст модели (промпт + ответ). Расшифровку под него обрезает LlmTranscriptPostProcessor. */
        const val CONTEXT_TOKENS = 4096

        /** Только текст ответа (без служебных каналов вроде «размышлений»). */
        fun textOf(message: Message): String =
            message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }
}
