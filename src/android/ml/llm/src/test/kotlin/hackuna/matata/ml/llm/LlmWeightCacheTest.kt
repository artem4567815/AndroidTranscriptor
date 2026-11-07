package hackuna.matata.ml.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlmWeightCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `имя кэша как у LiteRT-LM`() {
        val model = tmp.newFile("model.litertlm").apply { writeBytes(ByteArray(10)); setLastModified(1_794_071_073_500) }
        assertEquals("model.litertlm_1794071073_10.xnnpack_cache", LlmWeightCache.name(model))
    }

    @Test
    fun `старые кэши удаляются, текущий и чужие файлы остаются`() {
        val model = tmp.newFile("model.litertlm").apply { writeBytes(ByteArray(10)) }
        val dir = tmp.newFolder("cache")
        val current = dir.resolve(LlmWeightCache.name(model)).apply { writeText("x") }
        val stale = dir.resolve("model.litertlm_1700000000_10.xnnpack_cache").apply { writeText("x") }
        val other = dir.resolve("other.bin").apply { writeText("x") }

        assertTrue(LlmWeightCache.exists(dir, model))
        assertEquals(1, LlmWeightCache.prune(dir, model))
        assertTrue(current.exists())
        assertFalse(stale.exists())
        assertTrue(other.exists())
    }
}
