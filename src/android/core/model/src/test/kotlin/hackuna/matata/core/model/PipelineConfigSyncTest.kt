package hackuna.matata.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * config/pipeline.default.json (им пользуется Python-стенд) должен совпадать с PipelineConfig():
 * подобрали порог на стенде — поменяйте его и здесь, и там, иначе тест упадёт.
 */
class PipelineConfigSyncTest {
    @Test
    fun `pipeline_default_json совпадает с PipelineConfig по умолчанию`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "config/pipeline.default.json") }
            .first { it.exists() }
        // ignoreUnknownKeys = false: лишний ключ в JSON — тоже ошибка.
        val fromJson = Json.decodeFromString(PipelineConfig.serializer(), file.readText())
        assertEquals(PipelineConfig(), fromJson)
        // и наоборот: каждого поля PipelineConfig ключ есть в JSON (иначе выше подставилось бы значение по умолчанию)
        val keys = Json.parseToJsonElement(file.readText()).let { (it as kotlinx.serialization.json.JsonObject).keys }
        val fields = Json { encodeDefaults = true }.encodeToJsonElement(PipelineConfig.serializer(), PipelineConfig())
            .let { (it as kotlinx.serialization.json.JsonObject).keys }
        assertEquals(fields, keys)
    }
}
