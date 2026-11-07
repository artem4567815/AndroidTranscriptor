package hackuna.matata.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptJsonTest {

    /** Пример из ТЗ организаторов (раздел 2 промпта). */
    private val sample = """
        {
          "transcript": [
            { "speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "Добрый день!" }
          ]
        }
    """.trimIndent()

    @Test
    fun `сериализация совпадает с примером по ключам и типам`() {
        val t = Transcript(listOf(Utterance("speaker_1", 1250, 4380, "Добрый день!")))
        val ours = Json.parseToJsonElement(TranscriptJson.encode(t)).jsonObject
        val expected = Json.parseToJsonElement(sample).jsonObject

        assertEquals(expected.keys, ours.keys)
        val e = expected["transcript"]!!.jsonArray[0].jsonObject
        val o = ours["transcript"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), o.keys)
        assertEquals(e.keys, o.keys)
        for (key in e.keys) {
            val ev = e[key] as JsonPrimitive
            val ov = o[key] as JsonPrimitive
            assertEquals("тип поля $key", ev.isString, ov.isString)
        }
        assertTrue((o["start_ms"] as JsonPrimitive).long == 1250L)
        assertFalse((o["end_ms"] as JsonPrimitive).isString)
        assertEquals(expected, ours)
    }

    @Test
    fun `пример ТЗ читается и пишется без потерь`() {
        val t = TranscriptJson.decode(sample)
        assertEquals("speaker_1", t.transcript.single().speakerId)
        assertEquals(t, TranscriptJson.decode(TranscriptJson.encode(t, pretty = false)))
    }

    @Test
    fun `пустая расшифровка — пустой массив`() {
        val o = Json.parseToJsonElement(TranscriptJson.encode(Transcript(emptyList()))) as JsonObject
        assertEquals(JsonArray(emptyList()), o["transcript"])
    }

    @Test
    fun `rtf считается по сумме этапов`() {
        val r = TranscriptionResult(
            Transcript(emptyList()), null, null, emptyMap(),
            mapOf(Stage.ASR to 3000L, Stage.DIARIZATION to 1000L), audioDurationMs = 20_000,
        )
        assertEquals(0.2, r.rtf, 1e-9)
    }

    @Test
    fun `коды ошибок`() {
        assertEquals(ErrorCode.OUT_OF_MEMORY, OutOfMemoryError().toErrorCode())
        assertEquals(ErrorCode.DECODE_FAILED, RuntimeException(StenoException(ErrorCode.DECODE_FAILED, "x")).toErrorCode())
        assertEquals(ErrorCode.UNKNOWN, IllegalStateException().toErrorCode())
    }
}
