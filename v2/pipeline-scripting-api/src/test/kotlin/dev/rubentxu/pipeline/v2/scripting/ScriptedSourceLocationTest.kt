package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScriptedSourceLocationTest {

    @Test
    fun `source location deterministically derives shell and dynamic scope identities`() {
        val location = ScriptedSourceLocation(
            sourceId = ScriptedSourceId("pipelines/release.pipeline.kts"),
            line = 12,
            column = 7,
        )

        assertEquals(
            "pipelines/release.pipeline.kts:12:7:sh",
            location.shellCallSite().value,
        )
        assertEquals(
            "loop:pipelines/release.pipeline.kts:12:7[2]",
            location.loopScope(iteration = 2).value,
        )
        assertEquals(
            "block:credentials@pipelines/release.pipeline.kts:12:7",
            location.blockScope(ScriptedBlockName("credentials")).value,
        )
    }
}
