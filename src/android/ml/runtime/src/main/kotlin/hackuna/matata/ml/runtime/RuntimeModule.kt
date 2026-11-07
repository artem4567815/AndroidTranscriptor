package hackuna.matata.ml.runtime

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val runtimeModule = module {
    single { ModelManager(androidContext()) }
}
