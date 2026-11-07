package hackuna.matata.ml.llm

import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.StenoException
import hackuna.matata.ml.runtime.ModelManager

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

object LlmModelIds {
    const val MODEL = "llm"
}

class LiteRtLmEngine(
    private val models: ModelManager,
    @Suppress("unused") private val config: PipelineConfig,
) : LlmEngine {

    // Загруженная языковая модель
    private var engine: Engine? = null

    init {
        if (models.path(LlmModelIds.MODEL) == null) {
            throw StenoException(
                ErrorCode.MODELS_MISSING,
                "LLM-модель не установлена"
            )
        }
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {

        // Проверяем входные данные
        require(prompt.isNotBlank()) {
            "Текст запроса пустой"
        }

        require(maxTokens > 0) {
            "maxTokens должен быть больше нуля"
        }

        // Загружаем модель, если она ещё не загружена
        val currentEngine = engine ?: Engine(
            EngineConfig(
                modelPath = models.require(LlmModelIds.MODEL),
                backend = Backend.CPU()
            )
        ).also { newEngine ->
            newEngine.initialize()
            engine = newEngine
        }

        // Создаём диалог с моделью
        currentEngine.createConversation().use { conversation ->

            val result = StringBuilder()

            // Отправляем запрос и получаем ответ
            conversation.sendMessageAsync(
                prompt,
                maxOutputToken = maxTokens
            ).collect { message ->

                result.append(message.toString())
            }

            // Возвращаем готовый текст
            result.toString().trim()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}

