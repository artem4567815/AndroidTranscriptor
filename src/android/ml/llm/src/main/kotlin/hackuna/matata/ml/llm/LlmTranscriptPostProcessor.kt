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
     * русский текст ≈ 3 символа на токен: 8500 символов ≈ 2850 токенов + инструкция ≈ 300 + ответ ≤ 640.
     */
    private val maxContextChars: Int = 8_500,
) : TranscriptPostProcessor {

    override suspend fun protocol(t: Transcript): String {
        val r = render(t)
        val answer = engine.generate(Prompts.summaryRequest(r.text), SUMMARY_TOKENS, Prompts.SUMMARY_SYSTEM)
        val text = SummaryAttribution.fix(Prompts.cleanSummary(answer, t), t)
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
        /** Изложение — 100–160 слов (~200–350 токенов на тестовых записях); запас, чтобы не обрезать. */
        const val SUMMARY_TOKENS = 640
    }
}

/**
 * Промпты подобраны на Qwen3-1.7B (docs/MODELS.md, замеры в docs/TESTS.md):
 *  - правила — в системном сообщении, расшифровка — отдельно: иначе маленькая модель
 *    начинает переписывать расшифровку в ответ;
 *  - полужёсткая форма «О чём разговор» + «Главное» (4–8 пунктов): при полной свободе модель
 *    сжимала итоги до 1–3 предложений, а жёсткий шаблон «Тема/Решения/Поручения» ломался на играх;
 *  - пункты — о содержании, участников не называть (кроме поручений): иначе модель приписывает
 *    ведущему рассказ гостя («Леша поделился хобби»), хотя он только спрашивал. Справка «кто сколько говорит»
 *    и имена в расшифровке делали хуже — модель придумывала реплики для каждого участника.
 */
internal object Prompts {
    val SUMMARY_SYSTEM = """
        Ты составляешь краткое изложение разговора по его расшифровке.

        Ответ дай в таком виде:
        О чём разговор: одно-два предложения.
        Главное:
        - от четырёх до восьми пунктов, в каждом одно-два предложения своими словами: конкретные факты, истории, мысли, решения и договорённости из расшифровки.

        Правила:
        - Пиши о содержании, а не о том, кто что сказал. Участников не называй. Исключение — решения и поручения: в них пиши, кто, что и к какому сроку сделает.
        - Пиши своими словами. Не цитируй и не переписывай реплики из расшифровки, не пересказывай их по очереди.
        - Если это игра или шутливая болтовня, так и напиши, а в пунктах — что происходило.
        - Обычный текст, без звёздочек и решёток. Ничего не придумывай: только то, что есть в расшифровке.
    """.trimIndent()

    fun summaryRequest(transcript: String) = "Расшифровка:\n${transcript}Составь изложение этого разговора."

    val CORRECT_SYSTEM = """
        Исправь в тексте только явные ошибки распознавания речи, пунктуацию и регистр.
        Не меняй смысл, не добавляй и не удаляй слова без необходимости. Верни только исправленный текст.
    """.trimIndent()

    /** Строка-цитата из расшифровки: «speaker_2: реплика» (с маркером списка или без). */
    private val quotedLine = Regex("""^\s*[-–—•*]?\s*speaker_\d+\s*:\s""", RegexOption.IGNORE_CASE)

    /**
     * Приводит ответ модели в порядок:
     *  - убирает Markdown и «эхо» расшифровки (заголовок «Расшифровка:» и всё после него);
     *  - выбрасывает строки-цитаты реплик и почти одинаковые пункты (модель 1,7B иногда повторяется);
     *  - «Сpeaker_1» с кириллической «С» → «speaker_1», чтобы интерфейс подставил имя.
     */
    fun cleanSummary(answer: String, transcript: Transcript? = null): String {
        val echo = Regex("""(?imu)^\s*<?расшифровка>?\s*:?\s*$""").find(answer)?.range?.first ?: answer.length
        val utterances = transcript?.transcript.orEmpty().map { words(it.text) }.filter { it.size >= 2 }.toSet()
        val seen = mutableListOf<Set<String>>()
        return answer.substring(0, echo)
            .replace("**", "").replace("__", "")
            .replace(Regex("""[СсCc]peaker_(\d+)"""), "speaker_$1")
            .lineSequence()
            .map { it.replace(Regex("""^\s*#+\s*"""), "").trimEnd() }
            .filterNot { it.isNotEmpty() && it.all { c -> c in "-—–=_*" } }
            .filterNot { quotedLine.containsMatchIn(it) }
            .filter { line ->
                val w = words(line)
                if (w.size < 3) return@filter true
                val duplicate = seen.any { jaccard(it, w) >= 0.8 } || w in utterances
                if (!duplicate) seen += w
                !duplicate
            }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() && b.isEmpty()) 1.0 else (a intersect b).size.toDouble() / (a union b).size

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
