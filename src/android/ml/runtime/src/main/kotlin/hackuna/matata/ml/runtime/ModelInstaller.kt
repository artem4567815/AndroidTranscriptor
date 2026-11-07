package hackuna.matata.ml.runtime

import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.StenoException
import java.io.File
import java.io.InputStream

/** Источник файлов моделей (в приложении — AssetManager, в тестах — каталог). */
interface ModelSource {
    fun readManifest(): String?
    fun exists(path: String): Boolean
    fun open(path: String): InputStream
}

/**
 * Чистая логика установки моделей: потоковое копирование с прогрессом, маркер версии,
 * повторно не копирует. Отделена от Android ради unit-тестов.
 */
class ModelInstaller(
    private val source: ModelSource,
    private val targetDir: File,
    private val freeBytes: () -> Long,
) {
    private val marker get() = File(targetDir, MARKER)

    fun manifest(): ModelManifest {
        val text = source.readManifest()
            ?: throw StenoException(ErrorCode.MODELS_MISSING, "Нет assets/models/manifest.json")
        return ModelManifest.parse(text)
    }

    fun isInstalled(m: ModelManifest = manifest()): Boolean =
        marker.takeIf { it.exists() }?.readText()?.trim() == markerValue(m) &&
            m.files.filter { it.required }.all { File(targetDir, it.path).exists() }

    /**
     * @param allowMissing debug: отсутствующие модели не ошибка (тогда работает FakePipeline).
     * @return true, если все обязательные модели на месте.
     */
    fun install(allowMissing: Boolean = false, onProgress: (Float, String) -> Unit = { _, _ -> }): Boolean {
        val m = manifest()
        if (isInstalled(m)) {
            onProgress(1f, "")
            return true
        }
        val available = m.files.filter { source.exists(it.path) }
        val missingRequired = m.files.filter { it.required && it !in available }
        if (missingRequired.isNotEmpty() && !allowMissing) {
            throw StenoException(
                ErrorCode.MODELS_MISSING,
                "Нет моделей: ${missingRequired.joinToString { it.path }}",
            )
        }
        val toCopy = available.filter { f ->
            val dst = File(targetDir, f.path)
            !(dst.exists() && f.sizeBytes > 0 && dst.length() == f.sizeBytes)
        }
        val total = toCopy.sumOf { it.sizeBytes.coerceAtLeast(1) }
        if (total > freeBytes() - RESERVE_BYTES) {
            throw StenoException(ErrorCode.NO_SPACE, "Нужно ${total / 1_000_000} МБ свободного места")
        }
        targetDir.mkdirs()
        var done = 0L
        val buffer = ByteArray(1 shl 20)
        for (f in toCopy) {
            val dst = File(targetDir, f.path)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.path + ".part")
            source.open(f.path).use { input ->
                tmp.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress((done.toFloat() / total).coerceAtMost(1f), f.path)
                    }
                }
            }
            if (!tmp.renameTo(dst)) throw StenoException(ErrorCode.UNKNOWN, "Не удалось записать ${f.path}")
        }
        onProgress(1f, "")
        val complete = missingRequired.isEmpty()
        if (complete) marker.writeText(markerValue(m))
        return complete
    }

    private fun markerValue(m: ModelManifest) = "${m.version}:${m.files.joinToString(",") { "${it.path}=${it.sizeBytes}" }.hashCode()}"

    companion object {
        const val MARKER = ".installed"
        const val RESERVE_BYTES = 50L * 1024 * 1024
    }
}
