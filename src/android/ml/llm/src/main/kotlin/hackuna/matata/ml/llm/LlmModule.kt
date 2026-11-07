package hackuna.matata.ml.llm

import hackuna.matata.core.model.LlmEngineFactory
import hackuna.matata.core.model.PostProcessorFactory
import hackuna.matata.ml.runtime.ModelManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val llmModule = module {
    single<LlmEngineFactory> {
        val context = androidContext()
        val cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }
        LlmEngineFactory { config ->
            val models = get<ModelManager>()
            models.path(LlmModelIds.MODEL)?.let { model ->
                LlmWeightCache.prune(cacheDir, model)
                LlmMemoryGuard.check(context, cacheDir, cached = LlmWeightCache.exists(cacheDir, model))
            }
            LiteRtLmEngine(models, config, cacheDir.path)
        }
    }
    single<PostProcessorFactory> { PostProcessorFactory { engine -> LlmTranscriptPostProcessor(engine) } }
}
