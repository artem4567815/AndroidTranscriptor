package hackuna.matata.ml.llm

import android.app.ActivityManager
import android.content.Context
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.StenoException
import java.io.File
import java.util.Locale

/**
 * Проверка перед загрузкой LLM. Если памяти мало, Android убивает приложение целиком
 * (lowmemorykiller, без исключения в коде) — на эмуляторе с 4 ГБ так и было. Поэтому лучше
 * не начинать: пайплайн поймает [StenoException] и вместо протокола покажет причину.
 */
internal object LlmMemoryGuard {
    /**
     * Пик своей (anon) памяти LLM на эмуляторе, контекст 4096: 2,1 ГБ при первом запуске (строится
     * кэш весов) и 2,0 ГБ дальше (docs/TESTS.md). Веса из кэша — сверх этого, но их система может вытеснить.
     */
    const val NEED_RAM_BYTES = 2_000L shl 20

    /** Кэш переупакованных весов при первом запуске — ~0,95 ГБ на диске. */
    const val NEED_DISK_BYTES = 1_100L shl 20

    fun check(context: Context, cacheDir: File, cached: Boolean) {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        checkRam(info.availMem)
        if (!cached) checkDisk(cacheDir.usableSpace)
    }

    fun checkRam(availBytes: Long) {
        if (availBytes < NEED_RAM_BYTES) {
            throw StenoException(
                ErrorCode.OUT_OF_MEMORY,
                "мало свободной памяти (свободно ${gb(availBytes)} ГБ, нужно около ${gb(NEED_RAM_BYTES)} ГБ). " +
                    "Закройте другие приложения и обработайте запись ещё раз.",
            )
        }
    }

    fun checkDisk(freeBytes: Long) {
        if (freeBytes < NEED_DISK_BYTES) {
            throw StenoException(
                ErrorCode.NO_SPACE,
                "мало места на телефоне (свободно ${gb(freeBytes)} ГБ, нужно около ${gb(NEED_DISK_BYTES)} ГБ).",
            )
        }
    }

    private fun gb(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024 * 1024)).replace('.', ',')
}
