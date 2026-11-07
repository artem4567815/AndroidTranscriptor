package hackuna.matata.pipeline

import hackuna.matata.core.model.AudioDecoder
import hackuna.matata.core.model.AudioPreprocessor
import hackuna.matata.core.model.Diarizer
import hackuna.matata.core.model.DiarizerFactory
import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.LlmEngine
import hackuna.matata.core.model.LlmEngineFactory
import hackuna.matata.core.model.MeetingProtocol
import hackuna.matata.core.model.PcmAudio
import hackuna.matata.core.model.PipelineConfig
import hackuna.matata.core.model.PipelineEvent
import hackuna.matata.core.model.PostProcessorFactory
import hackuna.matata.core.model.RecognizedSegment
import hackuna.matata.core.model.SpeakerSegment
import hackuna.matata.core.model.SpeechRecognizer
import hackuna.matata.core.model.SpeechRecognizerFactory
import hackuna.matata.core.model.SpeechRegion
import hackuna.matata.core.model.Stage
import hackuna.matata.core.model.StenoException
import hackuna.matata.core.model.Transcript
import hackuna.matata.core.model.TranscriptPostProcessor
import hackuna.matata.core.model.VadFactory
import hackuna.matata.core.model.VoiceActivityDetector
import hackuna.matata.core.model.toErrorCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTranscriptionPipelineTest {
    private val closed = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private val decoder = object : AudioDecoder {
        override suspend fun decode(uri: String) = PcmAudio(FloatArray(16_000 * 10))
    }
    private val pre = object : AudioPreprocessor {
        override fun process(audio: PcmAudio) = audio
    }
    private val vad = VadFactory {
        opened += "vad"
        object : VoiceActivityDetector {
            override fun detect(audio: PcmAudio) = listOf(SpeechRegion(0, 4000), SpeechRegion(5000, 9000))
            override fun close() { closed += "vad" }
        }
    }
    private val diar = DiarizerFactory {
        opened += "diar"
        assertTrue("vad должен быть закрыт до диаризации", "vad" in closed)
        object : Diarizer {
            override fun diarize(audio: PcmAudio, config: PipelineConfig, onProgress: (Float) -> Unit): List<SpeakerSegment> {
                onProgress(0.5f)
                return listOf(SpeakerSegment(7, 0, 2000), SpeakerSegment(7, 2300, 4000), SpeakerSegment(3, 5000, 9000))
            }
            override fun close() { closed += "diar" }
        }
    }
    private val asr = SpeechRecognizerFactory {
        opened += "asr"
        assertTrue("диаризация должна быть закрыта до ASR", "diar" in closed)
        var n = 0
        object : SpeechRecognizer {
            override fun recognize(samples: FloatArray, sampleRate: Int) =
                RecognizedSegment(0, samples.size * 1000L / sampleRate, listOf("раз", "два", "три")[n++])
            override fun close() { closed += "asr" }
        }
    }
    private val llm = LlmEngineFactory {
        opened += "llm"
        object : LlmEngine {
            override suspend fun generate(prompt: String, maxTokens: Int, system: String?) = ""
            override fun close() { closed += "llm" }
        }
    }
    private val pp = PostProcessorFactory {
        object : TranscriptPostProcessor {
            override suspend fun protocol(t: Transcript) = MeetingProtocol("протокол", mapOf("speaker_1" to "Председатель"))
            override suspend fun correct(t: Transcript) = t
        }
    }

    private fun pipeline(
        asrFactory: SpeechRecognizerFactory = asr,
        llmFactory: LlmEngineFactory = llm,
    ): DefaultTranscriptionPipeline {
        var t = 0L
        return DefaultTranscriptionPipeline(decoder, pre, vad, diar, asrFactory, llmFactory, pp, nowMs = { t += 10; t }, log = {})
    }

    @Test
    fun `полный прогон даёт склеенную расшифровку и тайминги`() = runTest {
        val events = pipeline().run("file.wav", PipelineConfig()).toList()
        val done = events.last() as PipelineEvent.Completed
        val r = done.result
        assertEquals(listOf("speaker_1", "speaker_2"), r.transcript.transcript.map { it.speakerId })
        // ASR получает участки VAD целиком: 0–4000 (оба отрезка спикера 7) и 5000–9000.
        assertEquals(listOf("раз", "два"), r.transcript.transcript.map { it.text })
        assertEquals(0L to 4000L, r.transcript.transcript[0].startMs to r.transcript.transcript[0].endMs)
        assertEquals("протокол", r.protocol)
        assertEquals(mapOf("speaker_1" to "Председатель"), r.speakerNames)
        assertNull(r.llmCorrected)
        assertEquals(setOf(Stage.DECODE, Stage.PREPROCESS, Stage.VAD, Stage.DIARIZATION, Stage.ASR, Stage.MERGE, Stage.LLM), r.timings.keys)
        assertEquals(10_000L, r.audioDurationMs)
        assertTrue(events.any { it is PipelineEvent.Progress && it.stage == Stage.DIARIZATION && it.fraction == 0.4f })
        assertEquals(listOf("vad", "diar", "asr", "llm"), opened)
        assertEquals(listOf("vad", "diar", "asr", "llm"), closed)
    }

    @Test
    fun `сбой ASR — Failed с этапом и движок закрыт`() = runTest {
        val failing = SpeechRecognizerFactory {
            object : SpeechRecognizer {
                override fun recognize(samples: FloatArray, sampleRate: Int): RecognizedSegment =
                    throw StenoException(ErrorCode.OUT_OF_MEMORY, "oom")
                override fun close() { closed += "asr" }
            }
        }
        val last = pipeline(asrFactory = failing).run("x", PipelineConfig()).toList().last() as PipelineEvent.Failed
        assertEquals(Stage.ASR, last.stage)
        assertEquals(ErrorCode.OUT_OF_MEMORY, last.error.toErrorCode())
        assertTrue("asr" in closed)
    }

    @Test
    fun `сбой LLM не валит обработку`() = runTest {
        val broken = LlmEngineFactory { throw NotImplementedError("TODO(роль 4)") }
        val events = pipeline(llmFactory = broken).run("x", PipelineConfig()).toList()
        val r = (events.last() as PipelineEvent.Completed).result
        assertTrue(r.protocol!!.startsWith("Протокол не составлен"))
        assertEquals(2, r.transcript.transcript.size)
    }

    @Test
    fun `расшифровка отдаётся до LLM, чтобы её успели сохранить`() = runTest {
        val events = pipeline().run("x", PipelineConfig()).toList()
        val ready = events.indexOfFirst { it is PipelineEvent.TranscriptReady }
        val llmStart = events.indexOfFirst { it is PipelineEvent.Progress && it.stage == Stage.LLM }
        assertTrue("TranscriptReady должен прийти до этапа LLM", ready in 0 until llmStart)
        val draft = (events[ready] as PipelineEvent.TranscriptReady).result
        assertEquals(2, draft.transcript.transcript.size)
        assertNull(draft.protocol)
    }

    @Test
    fun `LLM выключена — движок не создаётся`() = runTest {
        val r = pipeline().run("x", PipelineConfig(enableLlmProtocol = false, enableLlmCorrection = false)).toList().last()
        assertNotNull((r as PipelineEvent.Completed).result)
        assertTrue("llm" !in opened)
        assertNull(r.result.protocol)
    }
}
