package hackuna.matata.pipeline

import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.SpeakerText
import hackuna.matata.core.model.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceMergerTest {
    private val cfg = PipelineConfig(mergeGapMs = 700)

    @Test
    fun `склеивает реплики одного спикера с короткой паузой`() {
        val t = UtteranceMerger.merge(
            listOf(
                SpeakerText(3, 0, 1000, "Добрый"),
                SpeakerText(3, 1500, 2500, "день!"),
                SpeakerText(3, 3300, 4000, "Мяу."), // пауза 800 >= 700 — новая реплика
            ),
            cfg,
        )
        assertEquals(
            listOf(
                Utterance("speaker_1", 0, 2500, "Добрый день!"),
                Utterance("speaker_1", 3300, 4000, "Мяу."),
            ),
            t.transcript,
        )
    }

    @Test
    fun `не склеивает разных спикеров`() {
        val t = UtteranceMerger.merge(
            listOf(SpeakerText(0, 0, 1000, "а"), SpeakerText(1, 1100, 2000, "б"), SpeakerText(0, 2100, 3000, "в")),
            cfg,
        )
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), t.transcript.map { it.speakerId })
    }

    @Test
    fun `перенумерация по первому появлению и сортировка`() {
        val t = UtteranceMerger.merge(
            listOf(
                SpeakerText(5, 5000, 6000, "позже"),
                SpeakerText(2, 0, 1000, "раньше"),
                SpeakerText(9, 2000, 3000, "середина"),
            ),
            cfg,
        )
        assertEquals(listOf("раньше", "середина", "позже"), t.transcript.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3"), t.transcript.map { it.speakerId })
    }

    @Test
    fun `пустые сегменты выбрасываются и не мешают склейке`() {
        val t = UtteranceMerger.merge(
            listOf(
                SpeakerText(0, 0, 1000, "раз"),
                SpeakerText(1, 1100, 1200, "  "),
                SpeakerText(1, 1200, 1250, "."),
                SpeakerText(0, 1300, 2000, "два"),
            ),
            cfg,
        )
        assertEquals(listOf(Utterance("speaker_1", 0, 2000, "раз два")), t.transcript)
    }

    @Test
    fun `гарантия start меньше end и неотрицательное время`() {
        val t = UtteranceMerger.merge(listOf(SpeakerText(0, -50, -50, "x"), SpeakerText(1, 500, 400, "y")), cfg)
        assertTrue(t.transcript.all { it.startMs >= 0 && it.startMs < it.endMs })
    }

    @Test
    fun `пустой вход`() {
        assertTrue(UtteranceMerger.merge(emptyList(), cfg).transcript.isEmpty())
    }
}
