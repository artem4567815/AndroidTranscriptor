package hackuna.matata.ml.llm

import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.StenoException
import hackuna.matata.ml.runtime.ModelManager

object LlmModelIds {
    const val MODEL = "llm"
}

/**
 * Локальная LLM через LiteRT-LM (Kotlin API, без своего C++).
 * Основной кандидат — Gemma 4 E2B; если APK не влезает — меньшая модель, поддерживаемая LiteRT-LM.
 *
 * TODO(роль 4):
 *  - добавить implementation(libs.litertlm.android) в ml/llm/build.gradle.kts (версия в libs.versions.toml);
 *  - создать Engine(EngineConfig(modelPath = models.require(LlmModelIds.MODEL), backend = CPU)) и initialize();
 *  - generate(): открыть Conversation/Session, отправить prompt, собрать ответ, ограничить maxTokens;
 *  - close(): закрыть Conversation и Engine — освободить память сразу после этапа LLM.
 *  Точные имена классов сверить с документацией LiteRT-LM:
 *  https://github.com/google-ai-edge/LiteRT-LM (раздел Kotlin API).
 */
class LiteRtLmEngine(
    private val models: ModelManager,
    @Suppress("unused") private val config: PipelineConfig,
) : LlmEngine {
    init {
        if (models.path(LlmModelIds.MODEL) == null) {
            throw StenoException(ErrorCode.MODELS_MISSING, "LLM-модель не установлена")
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        throw NotImplementedError("TODO(роль 4): генерация через LiteRT-LM")
    }

    override fun close() {
        // TODO(роль 4): engine.close()
    }
}
