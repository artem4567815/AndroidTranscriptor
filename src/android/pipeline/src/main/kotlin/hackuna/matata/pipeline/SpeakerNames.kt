package hackuna.matata.pipeline

import hackuna.matata.core.model.Transcript

/**
 * Имена спикеров без LLM — из того, как люди представляются: «Я Лёша», «меня зовут Нюта».
 * LLM (Qwen3-1.7B) путала, кто есть кто: в интервью, где ведущий говорит «Я Лёша», имя доставалось гостье.
 * Правило надёжнее: имя получает только тот, кто его сам произнёс о себе.
 *
 * Для разговора двоих ещё одно правило: «в гостях у нас Нюта» — это собеседник того, кто так сказал.
 */
object SpeakerNames {

    /** Имя — русское слово с заглавной (латиница — это «Big fucking challenge», а не имя). */
    private const val NAME = """([А-ЯЁ][а-яё]{2,14})(?!\p{L})"""

    /** «я Лёша», «меня зовут Лёша» (не «я Вам»: формы вежливости — не имена). */
    private val selfIntro = Regex("""(?<!\p{L})(?:[Яя]|[Мм]еня\s+зовут)\s+$NAME""")

    /** «в гостях у нас Нюта», «у нас в гостях Нюта», «наш гость Нюта». */
    private val guestIntro = Regex("""(?<!\p{L})(?:[Вв]\s+гостях\s+у\s+нас|[Уу]\s+нас\s+в\s+гостях|[Нн]аш(?:а)?\s+гост(?:ь|ья))\s*,?\s+$NAME""")

    private val notNames = setOf("Вам", "Вас", "Вы", "Вами", "Ваш", "Ваша", "Ваше", "Ваши", "Вашей", "Вашего")

    fun fromIntroductions(t: Transcript): Map<String, String> {
        val speakers = t.transcript.map { it.speakerId }.distinct()
        val claims = mutableMapOf<String, MutableSet<String>>() // спикер → имена
        for (u in t.transcript) {
            selfIntro.findAll(u.text).map { it.groupValues[1] }.filter { it !in notNames }
                .forEach { claims.getOrPut(u.speakerId) { mutableSetOf() } += it }
            if (speakers.size == 2) {
                val other = speakers.first { it != u.speakerId }
                guestIntro.findAll(u.text).map { it.groupValues[1] }.filter { it !in notNames }
                    .forEach { claims.getOrPut(other) { mutableSetOf() } += it }
            }
        }
        // Только однозначные: у спикера одно имя, и его не называл о себе никто другой.
        val claimedBy = claims.values.flatten().groupingBy { it }.eachCount()
        return claims.filterValues { it.size == 1 }.mapValues { it.value.first() }.filterValues { claimedBy[it] == 1 }
    }
}
