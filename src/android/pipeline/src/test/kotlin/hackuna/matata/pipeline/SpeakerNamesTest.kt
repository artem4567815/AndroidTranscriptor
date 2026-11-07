package hackuna.matata.pipeline

import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.Utterance
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerNamesTest {
    private fun t(vararg lines: Pair<String, String>) =
        Transcript(lines.mapIndexed { i, (spk, text) -> Utterance(spk, i * 1000L, i * 1000L + 900, text) })

    @Test
    fun `интервью — ведущий представился сам, гостья по словам ведущего`() {
        val names = SpeakerNames.fromIntroductions(
            t(
                "speaker_2" to "Всё, всем привет. У нас сегодня подкаст для ОРГ. Я Лёша, в гостях у нас Нюта.",
                "speaker_1" to "Вот это ответственность. Привет, Лёш, привет всем!",
            ),
        )
        assertEquals(mapOf("speaker_2" to "Лёша", "speaker_1" to "Нюта"), names)
    }

    @Test
    fun `«меня зовут» и вежливое «Вам» — не имя`() {
        val names = SpeakerNames.fromIntroductions(
            t("speaker_1" to "Меня зовут Мария. Я Вам перезвоню.", "speaker_2" to "Хорошо.", "speaker_3" to "Да."),
        )
        assertEquals(mapOf("speaker_1" to "Мария"), names)
    }

    @Test
    fun `спорные случаи не подписываем`() {
        // Два имени у одного спикера и одно имя у двоих — молчим, чтобы не подписать не того.
        assertEquals(
            emptyMap<String, String>(),
            SpeakerNames.fromIntroductions(t("speaker_1" to "Я Игорь. Я Олег.", "speaker_2" to "Я Игорь? Нет.")),
        )
        // «В гостях у нас» в разговоре троих — неясно, кто гость.
        assertEquals(
            emptyMap<String, String>(),
            SpeakerNames.fromIntroductions(t("speaker_1" to "В гостях у нас Нюта.", "speaker_2" to "А", "speaker_3" to "Б")),
        )
    }

    @Test
    fun `латиница и обычная речь — не имена`() {
        assertEquals(
            emptyMap<String, String>(),
            SpeakerNames.fromIntroductions(t("speaker_1" to "Я Big fucking челлендж. Я поняла, что я люблю новое.")),
        )
    }
}
