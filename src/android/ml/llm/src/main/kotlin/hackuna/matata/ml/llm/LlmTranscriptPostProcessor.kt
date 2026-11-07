package hackuna.matata.ml.llm

import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptPostProcessor

/**
 * LLM-постобработка: протокол заседания, роли спикеров, опциональная правка текста.
 * Промпты и разбор ответов — чистая логика с тестами; генерация — в [LlmEngine].
 *
 * Правка никогда не перезаписывает сырой ASR: [correct] возвращает НОВЫЙ Transcript с теми же
 * спикерами и таймкодами, а пайплайн кладёт его в llmCorrected.
 */
class LlmTranscriptPostProcessor(
    private val engine: LlmEngine,
    private val maxContextChars: Int = 12_000,
) : TranscriptPostProcessor {

    override suspend fun protocol(t: Transcript): String =
        engine.generate(Prompts.protocol(render(t)), maxTokens = 1024).trim()

    override suspend fun speakerRoles(t: Transcript): Map<String, String> {
        val answer = engine.generate(Prompts.roles(render(t)), maxTokens = 256)
        val known = t.transcript.map { it.speakerId }.toSet()
        return Prompts.parseRoles(answer).filterKeys { it in known }
    }

    override suspend fun correct(t: Transcript): Transcript {
        // Правим по одной реплике: короткий контекст, ответ легко сопоставить с исходником.
        val fixed = t.transcript.map { u ->
            if (u.text.isBlank()) return@map u
            val answer = engine.generate(Prompts.correct(u.text), maxTokens = u.text.length / 2 + 64).trim()
            u.copy(text = Prompts.acceptCorrection(u.text, answer))
        }
        return Transcript(fixed)
    }

    /** Расшифровка в виде «speaker_1: текст» с обрезкой по длине контекста. */
    internal fun render(t: Transcript): String = buildString {
        for (u in t.transcript) {
            val line = "${u.speakerId}: ${u.text.trim()}\n"
            if (length + line.length > maxContextChars) {
                append("…\n")
                break
            }
            append(line)
        }
    }
}

internal object Prompts {
    fun protocol(transcript: String) = """
        Ты секретарь заседания. По расшифровке составь краткий протокол на русском языке:
        повестка, обсуждаемые вопросы, принятые решения, ответственные. Не выдумывай фактов,
        которых нет в расшифровке. Спикеров называй так, как они обозначены (speaker_N).

        Расшифровка:
        $transcript
        Протокол:
    """.trimIndent()

    fun roles(transcript: String) = """
        Определи роли участников разговора (например: Председатель, Секретарь, Докладчик).
        Ответь строго строками вида «speaker_N: Роль», по одной на участника, без пояснений.
        Если роль неясна — пропусти участника.

        Расшифровка:
        $transcript
        Роли:
    """.trimIndent()

    fun correct(text: String) = """
        Исправь в тексте только явные ошибки распознавания речи, пунктуацию и регистр.
        Не меняй смысл, не добавляй и не удаляй слова без необходимости. Верни только исправленный текст.

        Текст: $text
        Исправленный текст:
    """.trimIndent()

    private val roleLine = Regex("""^\s*[-*•]?\s*(speaker_\d+)\s*[:—–-]\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

    fun parseRoles(answer: String): Map<String, String> = answer.lineSequence()
        .mapNotNull { roleLine.find(it) }
        .associate { m -> m.groupValues[1].lowercase() to m.groupValues[2].trim().trimEnd('.').take(40) }

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

