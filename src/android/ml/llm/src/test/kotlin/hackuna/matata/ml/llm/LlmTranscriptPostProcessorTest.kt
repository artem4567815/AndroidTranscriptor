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

    /** Ответ Qwen3-1.7B на тестовую планёрку — свободный текст (с остатками Markdown и пробелами, как бывает). */
    private val meetingAnswer = """
        **В разговоре** обсуждались сроки запуска приложения и бюджет на тестирование.  
        ---
        Релиз-кандидат будет к 15 ноября, скрипт нагрузочного тестирования — до 20 ноября.  
    """.trimIndent()

    @Test
    fun `итоги — один запрос, свободный текст без Markdown`() = runTest {
        val engine = ScriptedEngine { meetingAnswer }
        val text = LlmTranscriptPostProcessor(engine).protocol(t)

        assertEquals(1, engine.prompts.size)
        assertTrue(engine.prompts.single().contains("speaker_1: открываем заседание"))
        assertEquals(Prompts.SUMMARY_SYSTEM, engine.systems.single())
        assertEquals(
            "В разговоре обсуждались сроки запуска приложения и бюджет на тестирование.\n" +
                "Релиз-кандидат будет к 15 ноября, скрипт нагрузочного тестирования — до 20 ноября.",
            text,
        )
    }

    @Test
    fun `промпт не навязывает шаблон и не просит роли`() {
        // Жёсткий шаблон давал кривые итоги на играх, роли модель путала (docs/TESTS.md).
        listOf("Тема:", "Решения:", "Поручения:", "Участники:").forEach {
            assertFalse(it, Prompts.SUMMARY_SYSTEM.contains(it))
        }
    }

    @Test
    fun `эхо расшифровки отрезается`() {
        val answer = "Тема: релиз.\n\nПоручения:\n- нет\n\nРасшифровка:\nspeaker_1: Добрый день"
        assertEquals("Тема: релиз.\n\nПоручения:\n- нет", Prompts.cleanSummary(answer))
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
        val pp = LlmTranscriptPostProcessor(ScriptedEngine { "Говорили о заседании." }, maxContextChars = 40)
        val r = pp.render(t)
        assertTrue(r.text.endsWith("…\n"))
        assertEquals(1000L, r.truncatedAtMs)
        assertTrue(pp.protocol(t).contains("Итоги по первым 1 мин записи"))

        assertNull(LlmTranscriptPostProcessor(ScriptedEngine { "" }).render(t).truncatedAtMs)
    }
}
