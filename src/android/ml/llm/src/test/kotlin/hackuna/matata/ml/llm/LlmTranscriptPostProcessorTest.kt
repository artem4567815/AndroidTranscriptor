package hackuna.matata.ml.llm

import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.Utterance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTranscriptPostProcessorTest {
    private val t = Transcript(
        listOf(
            Utterance("speaker_1", 0, 1000, "открываем заседание"),
            Utterance("speaker_2", 1200, 2000, "мяу"),
        ),
    )

    private class ScriptedEngine(val answer: (String) -> String) : LlmEngine {
        val prompts = mutableListOf<String>()
        val systems = mutableListOf<String?>()
        override suspend fun generate(prompt: String, maxTokens: Int, system: String?): String {
            prompts += prompt
            systems += system
            return answer(prompt)
        }
        override fun close() = Unit
    }

    /** Ответ Qwen3-1.7B на тестовую планёрку (с лишними пробелами в концах строк, как у модели). */
    private val meetingAnswer = """
        Участники:
        speaker_1 — руководитель, сессия.
        speaker_2 — технический специалист, сопровождение.
        speaker_9 — призрак

        Тема: Планёрка по подготовке мобильного приложения.

        **Решения:**
        - Релиз-кандидат к 15 ноября.
        ---
        Поручения:
        - speaker_2 — подготовить сценарий демонстрации.
    """.trimIndent()

    @Test
    fun `один запрос даёт протокол и роли известных спикеров`() = runTest {
        val engine = ScriptedEngine { meetingAnswer }
        val p = LlmTranscriptPostProcessor(engine).protocol(t)

        assertEquals(mapOf("speaker_1" to "Руководитель", "speaker_2" to "Технический специалист"), p.roles)
        assertEquals(1, engine.prompts.size)
        assertTrue(engine.prompts.single().contains("speaker_1: открываем заседание"))
        assertEquals(Prompts.PROTOCOL_SYSTEM, engine.systems.single())
        // Markdown и линии убраны, хвостовые пробелы тоже.
        assertFalse(p.text.contains("**"))
        assertFalse(p.text.lines().any { it == "---" || it.endsWith(" ") })
        assertTrue(p.text.contains("Решения:"))
    }

    @Test
    fun `роли в строку через точку с запятой и через запятую`() {
        assertEquals(
            mapOf("speaker_1" to "Секретарь", "speaker_2" to "Председатель"),
            Prompts.parseRoles("Участники: speaker_1 – секретарь, speaker_2 – председатель.\nТема: сметана"),
        )
    }

    @Test
    fun `перечисление реплик вместо ролей не принимается`() {
        val game = "Участники: speaker_1 – Птица; speaker_2 – Старика; speaker_1 – Аллигатор.\nТема: игра"
        assertEquals(emptyMap<String, String>(), Prompts.parseRoles(game))
    }

    @Test
    fun `без раздела участников ролей нет`() {
        assertEquals(emptyMap<String, String>(), Prompts.parseRoles("Тема: что-то\nПоручения:\n- speaker_1 — сделать"))
    }

    @Test
    fun `эхо расшифровки отрезается`() {
        val answer = "Тема: релиз.\n\nПоручения:\n- нет\n\nРасшифровка:\nspeaker_1: Добрый день"
        assertEquals("Тема: релиз.\n\nПоручения:\n- нет", Prompts.cleanProtocol(answer))
    }

    @Test
    fun `правка сохраняет таймкоды и отбрасывает галлюцинации`() = runTest {
        val engine = ScriptedEngine { p ->
            if (p.contains("открываем")) "Открываем заседание." else "Мяу мяу мяу мяу мяу мяу мяу мяу"
        }
        val fixed = LlmTranscriptPostProcessor(engine).correct(t)
        assertEquals("Открываем заседание.", fixed.transcript[0].text)
        assertEquals("мяу", fixed.transcript[1].text) // слишком длинный ответ — оставили ASR
        assertEquals(t.transcript.map { it.startMs to it.endMs }, fixed.transcript.map { it.startMs to it.endMs })
        assertEquals("открываем заседание", t.transcript[0].text) // исходник не тронут
        assertTrue(engine.systems.all { it == Prompts.CORRECT_SYSTEM })
    }

    @Test
    fun `контекст обрезается и протокол помечается`() = runTest {
        val pp = LlmTranscriptPostProcessor(ScriptedEngine { "Тема: заседание." }, maxContextChars = 40)
        val r = pp.render(t)
        assertTrue(r.text.endsWith("…\n"))
        assertEquals(1000L, r.truncatedAtMs)
        assertTrue(pp.protocol(t).text.contains("по первым 1 мин записи"))

        assertNull(LlmTranscriptPostProcessor(ScriptedEngine { "" }).render(t).truncatedAtMs)
    }
}
