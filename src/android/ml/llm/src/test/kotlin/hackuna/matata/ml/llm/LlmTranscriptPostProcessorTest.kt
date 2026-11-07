package hackuna.matata.ml.llm

import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.Utterance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        override suspend fun generate(prompt: String, maxTokens: Int): String = answer(prompt).also { prompts += prompt }
        override fun close() = Unit
    }

    @Test
    fun `роли разбираются и фильтруются по известным спикерам`() = runTest {
        val engine = ScriptedEngine { "speaker_1: Председатель.\n- speaker_2 — Кот\nspeaker_9: Призрак\nмусор" }
        val roles = LlmTranscriptPostProcessor(engine).speakerRoles(t)
        assertEquals(mapOf("speaker_1" to "Председатель", "speaker_2" to "Кот"), roles)
        assertTrue(engine.prompts.single().contains("speaker_1: открываем заседание"))
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
    }

    @Test
    fun `контекст обрезается`() {
        val pp = LlmTranscriptPostProcessor(ScriptedEngine { "" }, maxContextChars = 30)
        assertTrue(pp.render(t).endsWith("…\n"))
    }
}
