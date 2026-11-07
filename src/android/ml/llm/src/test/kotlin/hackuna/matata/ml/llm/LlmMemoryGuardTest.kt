package hackuna.matata.ml.llm

import hackuna.matata.core.model.ErrorCode
import hackuna.matata.core.model.StenoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmMemoryGuardTest {
    private val gb = 1L shl 30

    @Test
    fun `хватает памяти — молчит`() {
        LlmMemoryGuard.checkRam(3 * gb)
        LlmMemoryGuard.checkDisk(2 * gb)
    }

    @Test
    fun `мало ОЗУ — понятная причина и код OUT_OF_MEMORY`() {
        val e = assertThrows(StenoException::class.java) { LlmMemoryGuard.checkRam(gb + gb / 2) }
        assertEquals(ErrorCode.OUT_OF_MEMORY, e.code)
        assertTrue(e.message!!, e.message!!.contains("свободно 1,5 ГБ"))
    }

    @Test
    fun `мало места под кэш весов — NO_SPACE`() {
        val e = assertThrows(StenoException::class.java) { LlmMemoryGuard.checkDisk(gb / 2) }
        assertEquals(ErrorCode.NO_SPACE, e.code)
    }
}
