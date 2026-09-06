package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class KotlinScriptedSourceMapperTest {

    @Test
    fun `maps unqualified shell calls to stable source locations`() {
        val mapping = KotlinScriptedSourceMapper().map(
            ScriptedSource(
                sourceId = ScriptedSourceId("pipelines/release.pipeline.kts"),
                text =
                    """
                    val command = "echo release"
                    if (true) {
                        sh(command)
                        sh(script = "printf done", returnStatus = ReturnStatus)
                    }
                    """.trimIndent(),
            ),
        )

        assertTrue(mapping is ScriptedSourceMapping.Mapped)
        val mapped = mapping as ScriptedSourceMapping.Mapped
        assertEquals(
            listOf(
                ScriptedSourceLocation(ScriptedSourceId("pipelines/release.pipeline.kts"), 3, 5),
                ScriptedSourceLocation(ScriptedSourceId("pipelines/release.pipeline.kts"), 4, 5),
            ),
            mapped.shellCalls,
        )
    }
}
