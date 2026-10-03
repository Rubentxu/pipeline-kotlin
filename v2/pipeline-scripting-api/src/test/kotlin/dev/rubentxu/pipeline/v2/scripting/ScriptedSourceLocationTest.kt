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
            "pipelines/release.pipeline.kts:12:7:sh:none",
            location.shellCallSite(ScriptedShellReturnMode.NONE).value,
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

    /**
     * S4-A1. The return mode is PART of the durable shell identity, so the three
     * legal shapes of `sh(...)` at one source position are three identities.
     *
     * This is the law the mode-in-identity exists to protect, and it is asserted
     * as a property over the closed enum rather than as three more expected
     * strings: a fourth mode added later must also be distinct, so enumerating
     * cases here would eventually assert less than the code guarantees.
     *
     * Before S4-A1 the eager and stdout-returning forms were separated by a second
     * method, `shReturnStdoutCallSite()`. That put the same invariant in two places
     * — the `sh` spelling could be forgotten — and the mapper could in fact never
     * select it, so the separation existed only on paper.
     */
    @Test
    fun `S4-A1 - every shell return mode at one position is its own durable identity`() {
        val location = ScriptedSourceLocation(
            sourceId = ScriptedSourceId("pipelines/release.pipeline.kts"),
            line = 12,
            column = 7,
        )

        val identities = ScriptedShellReturnMode.entries.associateWith { mode ->
            location.shellCallSite(mode).value
        }

        assertEquals(
            ScriptedShellReturnMode.entries.size,
            identities.values.toSet().size,
            "two shell return modes at the same position produced the same identity: $identities",
        )
        // Determinism: the same location and mode must always derive the same id,
        // or a resumed run would not find the durable row it wrote.
        ScriptedShellReturnMode.entries.forEach { mode ->
            assertEquals(
                location.shellCallSite(mode).value,
                identities.getValue(mode),
                "shell identity for $mode is not deterministic",
            )
        }
    }
}
