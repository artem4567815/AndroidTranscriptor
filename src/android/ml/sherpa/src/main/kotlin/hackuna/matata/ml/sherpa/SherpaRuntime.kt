package hackuna.matata.ml.sherpa

import android.util.Log

/**
 * Обёртка над нативной частью sherpa-onnx.
 *
 * Kotlin API-файлы лежат рядом без изменения пакета `com.k2fsa.sherpa.onnx` (к нему привязаны
 * имена JNI-функций), а `.so` — в `src/main/jniLibs/arm64-v8a` (кладёт `scripts/fetch_sherpa.sh`).
 * В CI `.so` нет: модуль компилируется, но [isAvailable] вернёт false.
 */
object SherpaRuntime {
    const val VERSION = "2.0.1"
    private const val TAG = "Sherpa"

    @Volatile private var loaded: Boolean? = null

    /** Пытается загрузить libsherpa-onnx-jni.so; результат кэшируется. */
    fun isAvailable(): Boolean = loaded ?: synchronized(this) {
        loaded ?: runCatching { System.loadLibrary("sherpa-onnx-jni") }
            .onFailure { Log.w(TAG, "libsherpa-onnx-jni.so не найдена: запустите scripts/fetch_sherpa.sh", it) }
            .isSuccess.also { loaded = it }
    }
}
