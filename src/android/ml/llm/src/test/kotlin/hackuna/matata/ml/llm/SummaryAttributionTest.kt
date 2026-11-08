package hackuna.matata.ml.llm

import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.Utterance
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryAttributionTest {
    private fun t(vararg lines: Pair<String, String>) =
        Transcript(lines.mapIndexed { i, (spk, text) -> Utterance(spk, i * 1000L, i * 1000L + 900, text) })

    /** Как в живом интервью: ведущий (speaker_2) коротко спрашивает, гостья (speaker_1) рассказывает. */
    private val interview = t(
        "speaker_2" to "Всем привет, у нас сегодня подкаст. Расскажешь чуть о себе?",
        "speaker_1" to "Я двадцать лет проработала в корпорациях, сейчас развиваю стартап, произвожу аксессуары из паракорда, " +
            "это верёвка страховочная, пояса, ремешки, подвески, склад и сборщики, логистика.",
        "speaker_2" to "А как ты решила, что именно аксессуары, именно этот бизнес?",
        "speaker_1" to "Получилось случайно. Я годами вязала носки, шарфы, потом нашла красивые верёвки из альпинизма, " +
            "моё бывшее хобби туризм и альпинизм, а яхтинг нынешнее хобби, и начала плести штучки для друзей.",
        "speaker_2" to "А что люди обычно в тебе не видят, что-то личное?",
        "speaker_1" to "У меня синдром дефицита внимания, я люблю новые идеи и не люблю рутину, это помогает быть креативной.",
    )

    @Test
    fun `ответ с телефона — хобби гостьи, приписанное ведущему, возвращается гостье`() {
        val summary = """
            Главное:
            - speaker_1 работала в корпорациях и производит аксессуары из паракорда.
            - speaker_2 поделилась хобби — вязание, туризм, альпинизм — и упомянула, что её хобби связаны с бизнесом.
            - speaker_2 интересуется её хобби и задаёт вопросы о бизнесе.
        """.trimIndent()
        assertEquals(
            """
            Главное:
            - speaker_1 работала в корпорациях и производит аксессуары из паракорда.
            - speaker_1 поделилась хобби — вязание, туризм, альпинизм — и упомянула, что её хобби связаны с бизнесом.
            - speaker_2 интересуется её хобби и задаёт вопросы о бизнесе.
            """.trimIndent(),
            SummaryAttribution.fix(summary, interview),
        )
    }

    @Test
    fun `по словам — пункт про верёвки и альпинизм у того, кто это говорил`() {
        val line = "- speaker_2 обсуждает хобби — альпинизм, яхтинг и связь с верёвками."
        assertEquals("- speaker_1 обсуждает хобби — альпинизм, яхтинг и связь с верёвками.", SummaryAttribution.fix(line, interview))
    }

    @Test
    fun `верные строки и строки с двумя участниками не трогаем`() {
        val summary = "- speaker_1 рассказала о синдроме дефицита внимания.\n- speaker_1 и speaker_2 обсуждают бизнес."
        assertEquals(summary, SummaryAttribution.fix(summary, interview))
    }

    @Test
    fun `встреча троих — правило «кто спрашивает» не применяется`() {
        val meeting = t(
            "speaker_1" to "Сколько это стоит?",
            "speaker_2" to "Тридцать тысяч за два устройства, аренда дешевле.",
            "speaker_3" to "Я напишу скрипт нагрузочного тестирования до двадцатого.",
        )
        val line = "- speaker_1 рассказал про сроки выставки."
        assertEquals(line, SummaryAttribution.fix(line, meeting))
    }
}
