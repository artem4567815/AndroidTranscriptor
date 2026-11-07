package hackuna.matata.ml.llm

import hackuna.matata.core.model.LlmEngineFactory
import hackuna.matata.core.model.PostProcessorFactory
import org.koin.dsl.module

val llmModule = module {
    single<LlmEngineFactory> { LlmEngineFactory { config -> LiteRtLmEngine(get(), config) } }
    single<PostProcessorFactory> { PostProcessorFactory { engine -> LlmTranscriptPostProcessor(engine) } }
}
