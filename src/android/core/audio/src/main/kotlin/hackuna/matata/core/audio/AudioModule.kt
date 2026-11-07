package hackuna.matata.core.audio

import hackuna.matata.core.model.AudioDecoder
import hackuna.matata.core.model.AudioPreprocessor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val audioModule = module {
    single<AudioDecoder> { MediaCodecAudioDecoder(androidContext()) }
    single<AudioPreprocessor> { DefaultAudioPreprocessor() }
    factory { AudioRecorder() }
}
