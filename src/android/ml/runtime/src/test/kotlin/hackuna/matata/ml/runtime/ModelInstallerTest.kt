package hackuna.matata.ml.runtime

import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.StenoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelInstallerTest {
    private val src = Files.createTempDirectory("assets").toFile()
    private val dst = Files.createTempDirectory("files").toFile()

    private fun source(dir: File) = object : ModelSource {
        override fun readManifest() = File(dir, "manifest.json").takeIf { it.exists() }?.readText()
        override fun exists(path: String) = File(dir, path).exists()
        override fun open(path: String) = File(dir, path).inputStream()
    }

    private fun setup(withLlm: Boolean = true, withAsr: Boolean = true) {
        File(src, "manifest.json").writeText(
            """
            {"version": 1, "files": [
              {"id": "asr", "path": "asr/model.onnx", "sizeBytes": 3000000},
              {"id": "llm", "path": "llm/model.litertlm", "sizeBytes": 10, "required": false}
            ]}
            """.trimIndent(),
        )
        if (withAsr) File(src, "asr").apply { mkdirs() }.resolve("model.onnx").writeBytes(ByteArray(3_000_000) { it.toByte() })
        if (withLlm) File(src, "llm").apply { mkdirs() }.resolve("model.litertlm").writeBytes(ByteArray(10))
    }

    @Test
    fun `копирует с прогрессом и второй раз не копирует`() {
        setup()
        val progress = mutableListOf<Float>()
        val installer = ModelInstaller(source(src), dst) { Long.MAX_VALUE }
        assertTrue(installer.install { f, _ -> progress += f })
        assertEquals(3_000_000L, File(dst, "asr/model.onnx").length())
        assertTrue(progress.size > 2)
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })

        progress.clear()
        File(src, "asr/model.onnx").delete() // источник больше не нужен
        assertTrue(installer.install { f, _ -> progress += f })
        assertEquals(listOf(1f), progress)
    }

    @Test
    fun `нет обязательной модели — MODELS_MISSING, в debug — false`() {
        setup(withAsr = false)
        val installer = ModelInstaller(source(src), dst) { Long.MAX_VALUE }
        try {
            installer.install()
            fail()
        } catch (e: StenoException) {
            assertEquals(ErrorCode.MODELS_MISSING, e.code)
        }
        assertFalse(installer.install(allowMissing = true))
        assertTrue(File(dst, "llm/model.litertlm").exists())
    }

    @Test
    fun `опциональная LLM может отсутствовать`() {
        setup(withLlm = false)
        assertTrue(ModelInstaller(source(src), dst) { Long.MAX_VALUE }.install())
    }

    @Test
    fun `нехватка места — NO_SPACE`() {
        setup()
        try {
            ModelInstaller(source(src), dst) { 1000L }.install()
            fail()
        } catch (e: StenoException) {
            assertEquals(ErrorCode.NO_SPACE, e.code)
        }
    }

    @Test
    fun `нет манифеста — MODELS_MISSING`() {
        try {
            ModelInstaller(source(src), dst) { Long.MAX_VALUE }.install()
            fail()
        } catch (e: StenoException) {
            assertEquals(ErrorCode.MODELS_MISSING, e.code)
        }
    }
}
