package hackuna.matata.ml.runtime

import android.content.Context
import android.content.res.AssetManager
import android.os.StatFs
import android.util.Log
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.StenoException
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Модели на устройстве: при первом запуске потоково копирует их из `assets/models`
 * в `filesDir/models`, пишет маркер версии и повторно не копирует.
 * Движки получают абсолютные пути через [path] / [require].
 * Скачивания из сети здесь нет и быть не может: модели попадают в APK скриптом сборки.
 */
class ModelManager(context: Context) {
    val modelsDir: File = File(context.filesDir, "models")
    private val assets: AssetManager = context.assets
    private val installer = ModelInstaller(AssetModelSource(assets), modelsDir) { StatFs(context.filesDir.path).availableBytes }

    val manifest: ModelManifest by lazy {
        runCatching { installer.manifest() }.getOrElse { ModelManifest(0, emptyList()) }
    }

    /** Блокирующий вызов — только из Dispatchers.IO. */
    fun prepare(allowMissing: Boolean, onProgress: (Float, String) -> Unit): Boolean {
        val ok = installer.install(allowMissing, onProgress)
        Log.i(TAG, "prepare: complete=$ok dir=$modelsDir")
        return ok
    }

    fun isReady(): Boolean = runCatching { installer.isInstalled() }.getOrDefault(false)

    fun path(id: String): File? = manifest.file(id)?.let { File(modelsDir, it.path) }?.takeIf { it.exists() }

    /** Абсолютный путь к обязательному файлу модели или MODELS_MISSING. */
    fun require(id: String): String = path(id)?.absolutePath
        ?: throw StenoException(ErrorCode.MODELS_MISSING, "Модель '$id' не установлена")

    fun status(): List<ModelStatus> = manifest.files.map { ModelStatus(it, File(modelsDir, it.path).exists()) }

    private companion object {
        const val TAG = "ModelManager"
    }
}

private class AssetModelSource(private val assets: AssetManager) : ModelSource {
    override fun readManifest(): String? = try {
        assets.open("models/manifest.json").bufferedReader().use { it.readText() }
    } catch (e: FileNotFoundException) {
        null
    }

    override fun exists(path: String): Boolean = try {
        assets.open("models/$path").close()
        true
    } catch (e: Exception) {
        false
    }

    override fun open(path: String): InputStream = assets.open("models/$path", AssetManager.ACCESS_STREAMING)
}
