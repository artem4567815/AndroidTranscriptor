package hackuna.matata.ml.llm

import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptPostProcessor

/**
 * LLM-постобработка: итоги разговора и опциональная правка текста.
 * Промпты и разбор ответов — чистая логика с тестами; генерация — в [LlmEngine].
 *
 * Правка никогда не перезаписывает сырой ASR: [correct] возвращает НОВЫЙ Transcript с теми же
 * спикерами и таймкодами, а пайплайн кладёт его в llmCorrected.
 */
class LlmTranscriptPostProcessor(
    private val engine: LlmEngine,
    /**
     * Сколько символов расшифровки отдаём модели. Контекст Qwen3 в приложении — 4096 токенов,
     * русский текст ≈ 3 символа на токен: 9000 символов ≈ 3000 токенов + инструкция ≈ 200 + ответ ≤ 320.
     */
    private val maxContextChars: Int = 9_000,
) : TranscriptPostProcessor {

    override suspend fun protocol(t: Transcript): String {
        val r = render(t)
        val answer = engine.generate(Prompts.summaryRequest(r.text), SUMMARY_TOKENS, Prompts.SUMMARY_SYSTEM)
        val text = Prompts.cleanSummary(answer)
        if (text.isEmpty() || r.truncatedAtMs == null) return text
        val minutes = (r.truncatedAtMs + 59_999) / 60_000
        return "$text\n\n(Итоги по первым $minutes мин записи: вся расшифровка не помещается в память модели.)"
    }

    override suspend fun correct(t: Transcript): Transcript {
        // Правим по одной реплике: короткий контекст, ответ легко сопоставить с исходником.
        val fixed = t.transcript.map { u ->
            if (u.text.isBlank()) return@map u
            val answer = engine.generate(u.text, maxTokens = u.text.length / 2 + 64, system = Prompts.CORRECT_SYSTEM)
            u.copy(text = Prompts.acceptCorrection(u.text, answer.trim()))
        }
        return Transcript(fixed)
    }

    /** Расшифровка для модели и момент обрезки (конец последней вошедшей реплики), если всё не влезло. */
    internal data class Rendered(val text: String, val truncatedAtMs: Long?)

    /** Расшифровка в виде «speaker_1: текст» с обрезкой по длине контекста. */
    internal fun render(t: Transcript): Rendered {
        val sb = StringBuilder()
        var lastEndMs = 0L
        for (u in t.transcript) {
            val line = "${u.speakerId}: ${u.text.trim()}\n"
            if (sb.length + line.length > maxContextChars) {
                sb.append("…\n")
                return Rendered(sb.toString(), lastEndMs)
            }
            sb.append(line)
            lastEndMs = u.endMs
        }
        return Rendered(sb.toString(), null)
    }

    private companion object {
        /** Итоги — 3–5 предложений (~40–120 токенов на тестовых записях); запас, чтобы не обрезать. */
        const val SUMMARY_TOKENS = 320
    }
}

/**
 * Промпты подобраны на Qwen3-1.7B (docs/MODELS.md, замеры в docs/TESTS.md):
 *  - правила — в системном сообщении, расшифровка — отдельно: иначе маленькая модель
 *    начинает переписывать расшифровку в ответ;
 *  - одна простая задача — пересказ своими словами. Жёсткий шаблон («Тема / Обсуждали / Решения»)
 *    на играх и бытовых разговорах давал кривые итоги, а варианты «если встреча — так, если игра — так»
 *    модель 1,7B выполняет все сразу. Роли участников она путает и копирует примеры из промпта —
 *    поэтому их не просим (имена берутся из самопредставлений, pipeline/SpeakerNames).
 */
internal object Prompts {
    val SUMMARY_SYSTEM = """
        Ты пишешь короткое содержание разговора по его расшифровке. Участники в расшифровке обозначены speaker_1, speaker_2 и так далее.

        Напиши от трёх до пяти предложений своими словами: о чём был разговор и что в нём главное. Если были решения или договорённости — кто, что и к какому сроку — обязательно упомяни их. Если это игра или шутливая болтовня — так и скажи. Пиши обычным текстом, без списков и без Markdown. Не придумывай того, чего нет в расшифровке.
    """.trimIndent()

    fun summaryRequest(transcript: String) = "Расшифровка:\n${transcript}Кратко перескажи этот разговор."

    val CORRECT_SYSTEM = """
        Исправь в тексте только явные ошибки распознавания речи, пунктуацию и регистр.
        Не меняй смысл, не добавляй и не удаляй слова без необходимости. Верни только исправленный текст.
    """.trimIndent()

    /** Убирает остатки Markdown и «эхо» расшифровки, если модель всё же начала её повторять. */
    fun cleanSummary(answer: String): String {
        val echo = Regex("""(?imu)^\s*<?расшифровка>?\s*:?\s*$""").find(answer)?.range?.first ?: answer.length
        return answer.substring(0, echo)
            .replace("**", "").replace("__", "")
            .lineSequence()
            .map { it.replace(Regex("""^\s*#+\s*"""), "").trimEnd() }
            .filterNot { it.isNotEmpty() && it.all { c -> c in "-—–=_*" } }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    /**
     * Защита от «галлюцинаций»: правку принимаем, только если длина изменилась не сильно.
     * Иначе остаётся исходный текст ASR.
     */
    fun acceptCorrection(original: String, answer: String): String {
        val cleaned = answer.removePrefix("Исправленный текст:").trim().trim('"', '«', '»')
        if (cleaned.isEmpty()) return original
        val ratio = cleaned.length.toDouble() / original.length.coerceAtLeast(1)
        return if (ratio in 0.7..1.4) cleaned else original
    }
}
