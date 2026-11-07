package hackuna.matata.pipeline

import hackuna.matata.core.model.TranscriptionPipeline
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Реальный конвейер. Выбор между ним и FakePipeline (только debug) делает модуль app. */
val pipelineModule = module {
    single<TranscriptionPipeline>(named(REAL_PIPELINE)) {
        DefaultTranscriptionPipeline(get(), get(), get(), get(), get(), get(), get())
    }
}

const val REAL_PIPELINE = "real"
