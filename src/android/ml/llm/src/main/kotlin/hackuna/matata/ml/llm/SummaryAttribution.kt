package hackuna.matata.ml.llm

import hackuna.matata.core.model.Transcript

/**
 * Проверка «кто что сказал» в итогах без LLM. Qwen3-1.7B путает участников: в интервью пишет
 * «speaker_2 (ведущий) поделился хобби — вязание, туризм», хотя ведущий только задавал вопросы.
 * Промптом это не лечится (docs/TESTS.md), поэтому строку, где назван ровно один участник, сверяем с расшифровкой:
 *  1. по словам: ключевые слова строки почти все из реплик другого участника → это он;
 *  2. по роли (разговор двоих): участник говорит мало и много спрашивает, а строка утверждает, что он
 *     «поделился / рассказал / упомянул» — рассказывал его собеседник.
 * Имена потом подставляет интерфейс вместо speaker_N, поэтому исправлять достаточно обозначение.
 */
internal object SummaryAttribution {

    private val speakerRef = Regex("""speaker_\d+""", RegexOption.IGNORE_CASE)
    private val word = Regex("""[а-яё]+""")

    /** Рассказ о себе, а не вопрос: «поделился», «рассказала», «упомянул», «вспоминает»… */
    private val narrates = Regex(
        """(?<![а-яё])(поделил\w*|делит\w*|рассказ\w*|упомина\w*|упомянул\w*|вспомина\w*|признал\w*|признаёт\w*|описыва\w*)""",
        RegexOption.IGNORE_CASE,
    )

    /** Слова пересказа, которые ничего не говорят о том, чьи это мысли. */
    private val stop = (
        "это этот эта эти тот как так что чтобы когда если также тоже очень было были быть есть она оно они его её ему ней них " +
            "своим своих свой свою своей своём свое себя себе который которая которые которых разговор разговоре разговора " +
            "говорит говорят рассказывает рассказала рассказал делится делилась поделилась поделился упомянула упомянул " +
            "обсуждают обсуждает отмечает подчеркивает подчёркивает интересуется задает задаёт вопросы вопрос опыт опыта опытом " +
            "связаны связано связь между после через"
        ).split(" ").toSet()

    private fun stems(text: String) =
        word.findAll(text.lowercase()).map { it.value }.filter { it.length >= 4 && it !in stop }.map { it.take(5) }.toList()

    fun fix(summary: String, t: Transcript): String {
        val speakers = t.transcript.map { it.speakerId }.distinct()
        if (speakers.size < 2) return summary
        // Кто какие слова говорил: основа слова → спикер → сколько раз.
        val usage = HashMap<String, HashMap<String, Int>>()
        for (u in t.transcript) for (s in stems(u.text)) {
            usage.getOrPut(s) { HashMap() }.merge(u.speakerId, 1, Int::plus)
        }
        val (asker, narrator) = interviewRoles(t, speakers)
        return summary.lines().joinToString("\n") { line ->
            val ids = speakerRef.findAll(line).map { it.value.lowercase() }.toSet()
            if (ids.size != 1) return@joinToString line
            val named = ids.first()
            val fixed = byWords(line, named, usage)
                ?: if (named == asker && narrator != null && narrates.containsMatchIn(line)) narrator else null
            if (fixed == null || fixed == named) line else speakerRef.replace(line, fixed)
        }
    }

    /** Ключевые слова строки почти все из реплик другого участника (вес слова — доля его употреблений). */
    private fun byWords(line: String, named: String, usage: Map<String, Map<String, Int>>): String? {
        val score = HashMap<String, Double>()
        var evidence = 0
        for (s in stems(speakerRef.replace(line, " ")).toSet()) {
            val byWho = usage[s] ?: continue
            evidence++
            val total = byWho.values.sum().toDouble()
            byWho.forEach { (spk, n) -> score.merge(spk, n / total, Double::plus) }
        }
        if (evidence < 2) return null
        val best = score.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { it != named && best.value >= 2 * (score[named] ?: 0.0) + 0.5 }
    }

    /**
     * Разговор двоих, где один в основном спрашивает (≤ 35% слов, вопросы хотя бы в 30% реплик),
     * а другой рассказывает (≥ 60% слов). Иначе (встреча, игра) правило не применяем.
     */
    private fun interviewRoles(t: Transcript, speakers: List<String>): Pair<String?, String?> {
        if (speakers.size != 2) return null to null
        val words = speakers.associateWith { s -> t.transcript.filter { it.speakerId == s }.sumOf { it.text.split(' ').count(String::isNotBlank) } }
        val total = words.values.sum().coerceAtLeast(1)
        val asker = speakers.firstOrNull { s ->
            val own = t.transcript.filter { it.speakerId == s }
            words.getValue(s) * 100 <= total * 35 && own.count { '?' in it.text } * 10 >= own.size * 3
        } ?: return null to null
        val narrator = speakers.first { it != asker }
        return if (words.getValue(narrator) * 100 >= total * 60) asker to narrator else null to null
    }
}
