package hackuna.matata.core.data

import hackuna.matata.core.data.export.ExportFormat
import hackuna.matata.core.data.export.TranscriptExporter
import hackuna.matata.core.model.Stage
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.Utterance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExporterTest {
    private val t = Transcript(
        listOf(
            Utterance("speaker_2", 5000, 7500, "Мяу."),
            Utterance("speaker_1", 1250, 4380, "Добрый день!"),
        ),
    )
    private val result = StoredResult(
        recordingId = "r1",
        transcript = t,
        llmCorrected = Transcript(listOf(Utterance("speaker_1", 1250, 4380, "ПРАВКА LLM"))),
        protocol = "Протокол",
        speakerNames = mapOf("speaker_1" to "Председатель"),
        timings = mapOf(Stage.ASR to 1000L),
        audioDurationMs = 10_000,
    )

    @Test
    fun `json строго по формату и отсортирован`() {
        val json = TranscriptExporter.export(result, ExportFormat.JSON)
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("transcript"), root.keys)
        val items = root["transcript"]!!.jsonArray
        assertEquals(2, items.size)
        val first = items[0].jsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), first.keys)
        assertEquals("speaker_1", (first["speaker_id"] as JsonPrimitive).content)
        assertFalse((first["start_ms"] as JsonPrimitive).isString)
        assertEquals("1250", (first["start_ms"] as JsonPrimitive).content)
        // Правка LLM и имена спикеров в строгий экспорт не попадают.
        assertFalse(json.contains("ПРАВКА"))
        assertFalse(json.contains("Председатель"))
    }

    @Test
    fun `srt`() {
        val srt = TranscriptExporter.export(result, ExportFormat.SRT)
        val expected = """
            1
            00:00:01,250 --> 00:00:04,380
            Председатель (speaker_1): Добрый день!

            2
            00:00:05,000 --> 00:00:07,500
            speaker_2: Мяу.

        """.trimIndent() + "\n"
        assertEquals(expected, srt)
    }

    @Test
    fun `txt c протоколом`() {
        val txt = TranscriptExporter.export(result, ExportFormat.TXT)
        assertTrue(txt.startsWith("[00:00:01.250 – 00:00:04.380] Председатель (speaker_1): Добрый день!\n"))
        assertTrue(txt.contains("[00:00:05.000 – 00:00:07.500] speaker_2: Мяу."))
        assertTrue(txt.trimEnd().endsWith("Протокол"))
    }

    @Test
    fun `sanitize чинит нулевую длительность`() {
        val s = TranscriptExporter.sanitize(Transcript(listOf(Utterance("speaker_1", 100, 100, "x"))))
        assertTrue(s.transcript[0].startMs < s.transcript[0].endMs)
    }

    @Test
    fun `формат часов`() {
        assertEquals("01:02:05,250", TranscriptExporter.clock(3_725_250, ','))
        assertEquals(ExportFormat.SRT, ExportFormat.parse("srt"))
    }

    @Test
    fun `rtf результата`() {
        assertEquals(0.1, result.rtf, 1e-9)
    }
}
