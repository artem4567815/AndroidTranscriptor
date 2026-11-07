package hackuna.matata.ml.sherpa

import android.util.Log

object SherpaRuntime {
    const val VERSION = "2.0.1"
    private const val TAG = "Sherpa"

    @Volatile private var loaded: Boolean? = null
    fun isAvailable(): Boolean = loaded ?: synchronized(this) {
        loaded ?: runCatching { System.loadLibrary("sherpa-onnx-jni") }
            .onFailure { Log.w(TAG, "libsherpa-onnx-jni.so не найдена: запустите scripts/fetch_sherpa.sh", it) }
            .isSuccess.also { loaded = it }
    }
}
