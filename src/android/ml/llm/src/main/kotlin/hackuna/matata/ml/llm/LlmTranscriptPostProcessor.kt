package hackuna.matata.ml.llm

import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.MeetingProtocol
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptPostProcessor

/**
 * LLM-постобработка: протокол заседания с ролями участников и опциональная правка текста.
 * Промпты и разбор ответов — чистая логика с тестами; генерация — в [LlmEngine].
 *
 * Правка никогда не перезаписывает сырой ASR: [correct] возвращает НОВЫЙ Transcript с теми же
 * спикерами и таймкодами, а пайплайн кладёт его в llmCorrected.
 */
class LlmTranscriptPostProcessor(
    private val engine: LlmEngine,
    /**
     * Сколько символов расшифровки отдаём модели. Контекст Qwen3 в приложении — 4096 токенов,
     * русский текст ≈ 3 символа на токен: 9000 символов ≈ 3000 токенов + инструкция ≈ 300 + ответ ≤ 640.
     */
    private val maxContextChars: Int = 9_000,
) : TranscriptPostProcessor {

    override suspend fun protocol(t: Transcript): MeetingProtocol {
        val r = render(t)
        val answer = engine.generate(Prompts.protocolRequest(r.text), PROTOCOL_TOKENS, Prompts.PROTOCOL_SYSTEM)
        var text = Prompts.cleanProtocol(answer)
        if (text.isNotEmpty() && r.truncatedAtMs != null) {
            val minutes = (r.truncatedAtMs + 59_999) / 60_000
            text += "\n\n(Протокол составлен по первым $minutes мин записи: вся расшифровка не помещается в память модели.)"
        }
        val known = t.transcript.map { it.speakerId }.toSet()
        return MeetingProtocol(text, Prompts.parseRoles(answer).filterKeys { it in known })
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
        /** Протокол получается ~300–450 токенов; запас, чтобы не обрезать на полуслове. */
        const val PROTOCOL_TOKENS = 640
    }
}

/**
 * Промпты подобраны на Qwen3-1.7B (docs/MODELS.md, замеры в docs/TESTS.md):
 *  - правила — в системном сообщении, расшифровка — отдельно: иначе маленькая модель
 *    начинает переписывать расшифровку в ответ;
 *  - формат описан словами, без образца: образец модель копирует дословно («speaker_1 — ведущий»).
 */
internal object Prompts {
    val PROTOCOL_SYSTEM = """
        Ты секретарь. Тебе дают расшифровку разговора, участники в ней обозначены speaker_1, speaker_2 и так далее.
        Составь краткий протокол на русском языке.

        Правила:
        - пиши обычным текстом, без Markdown: без звёздочек, решёток и горизонтальных линий;
        - используй только факты из расшифровки, не придумывай дату, место, имена и сроки;
        - не пересказывай расшифровку по репликам и не повторяй её;
        - если разговор не деловой (игра, бытовая беседа), в разделах «Решения» и «Поручения» напиши «нет».

        Разделы ответа по порядку:
        Участники: по строке на каждого участника — его обозначение, тире и роль одним-двумя словами.
        Тема: одно предложение.
        Обсуждали: до пяти коротких пунктов.
        Решения: пункты или «нет».
        Поручения: пункты «кто — что — срок» или «нет».
    """.trimIndent()

    fun protocolRequest(transcript: String) = "Расшифровка:\n${transcript}Составь протокол."

    val CORRECT_SYSTEM = """
        Исправь в тексте только явные ошибки распознавания речи, пунктуацию и регистр.
        Не меняй смысл, не добавляй и не удаляй слова без необходимости. Верни только исправленный текст.
    """.trimIndent()

    /** Убирает остатки Markdown и «эхо» расшифровки, если модель всё же начала её повторять. */
    fun cleanProtocol(answer: String): String {
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

    private val participants = Regex("""(?imu)^\s*Участники\s*:""")
    private val nextSection = Regex("""(?imu)^\s*(Тема|Обсуждали|Решения|Поручения)\s*:""")
    private val roleItem = Regex("""(speaker_\d+)\s*[—–:-]\s*([^;,.\n(]+)""", setOf(RegexOption.IGNORE_CASE))

    /**
     * Роли из раздела «Участники:». Если у одного участника несколько разных «ролей» — модель
     * перечисляет реплики, а не роли (так бывает на играх и бытовых разговорах): такой ответ не берём.
     */
    fun parseRoles(answer: String): Map<String, String> {
        val start = participants.find(answer) ?: return emptyMap()
        val rest = answer.substring(start.range.last + 1)
        val section = rest.substring(0, nextSection.find(rest)?.range?.first ?: rest.length)
        val byId = roleItem.findAll(section)
            .map { m -> m.groupValues[1].lowercase() to cleanRole(m.groupValues[2]) }
            .filter { it.second.isNotEmpty() }
            .groupBy({ it.first }, { it.second })
        if (byId.values.any { it.distinct().size > 1 }) return emptyMap()
        return byId.mapValues { it.value.first() }
    }

    private fun cleanRole(raw: String): String =
        raw.replace("*", "").trim().take(40).trim().replaceFirstChar { it.uppercaseChar() }

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
