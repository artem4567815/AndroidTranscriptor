package hackuna.matata.ml.llm

import java.io.File

/**
 * Кэш переупакованных весов LiteRT-LM: файл `<модель>_<mtime в секундах>_<размер>.xnnpack_cache`
 * в каталоге кэша (схема имени — из LiteRT-LM 0.19.1, проверено на устройстве).
 */
internal object LlmWeightCache {
    private const val SUFFIX = ".xnnpack_cache"

    fun name(model: File) = "${model.name}_${model.lastModified() / 1000}_${model.length()}$SUFFIX"

    fun exists(dir: File, model: File) = File(dir, name(model)).exists()

    /**
     * Удаляет кэши прежних копий модели. Модель копируется из APK заново после обновления
     * приложения — у копии новый mtime, LiteRT строит новый кэш, а старый (~1 ГБ) остался бы навсегда.
     */
    fun prune(dir: File, model: File): Int {
        val keep = name(model)
        return dir.listFiles().orEmpty()
            .filter { it.name.endsWith(SUFFIX) && it.name != keep }
            .count { it.delete() }
    }
}
