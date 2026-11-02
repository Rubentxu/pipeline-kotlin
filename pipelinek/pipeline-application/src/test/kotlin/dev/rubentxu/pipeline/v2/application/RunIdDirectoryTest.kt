package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.RunId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The naming border of the last-run record.
 *
 * The guarantee: the durable name of a definition is the definition itself, and
 * an identity that cannot carry that name is refused at the border, with the
 * reason stated, leaving no durable location behind.
 *
 * The record of a definition is a file whose name IS the definition identity,
 * exactly as the lease of a run is a file whose name is the run identity. So the
 * same law applies: a name that depends on the filesystem's case distinction
 * would let two definitions share one record, and `--resume` would read the
 * record of the other one — the run of a different definition — without any sign
 * that they were not the definition it asked for. On a volume that folds case
 * (APFS by default, exFAT), `MyPipeline` and `mypipeline` are the same file.
 *
 * The identities the product mints are content digests, so they are already
 * lowercase; the rule is here so that the guarantee does not depend on that
 * coincidence.
 *
 * The third test is the other side of the same discrimination: a guard that
 * refused every identity would satisfy the refusal rows and break the product.
 */
class RunIdDirectoryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a nameable definition names its own record`() {
        val root = tempDir.resolve("last-run")
        val definitionId = DefinitionId("9f1c2e5a0b3d4f6a8c1e2d4b6f8a0c12")

        RunIdDirectory(root).record(definitionId, RunId("run-1"), CorrelationId("corr-1"))

        val found = assertInstanceOf(
            StoredRunId.Found::class.java,
            RunIdDirectory(root).findLastRunId(definitionId),
        )
        assertEquals(RunId("run-1"), found.runId)
        assertTrue(
            Files.isRegularFile(root.resolve("9f1c2e5a0b3d4f6a8c1e2d4b6f8a0c12")),
            "the record must be named after the definition, verbatim",
        )
    }

    @Test
    fun `an identity that only differs in case cannot name the record`() {
        val root = tempDir.resolve("last-run")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            RunIdDirectory(root).record(DefinitionId("MyPipeline"), RunId("run-1"))
        }
        assertTrue(
            thrown.message?.contains("MyPipeline") == true,
            "the refusal must name the identity it refused: <${thrown.message}>",
        )
        assertTrue(
            thrown.message?.contains("lowercase") == true,
            "the refusal must name the reason it refuses: <${thrown.message}>",
        )
    }

    @Test
    fun `a refused identity leaves no durable location behind`() {
        val root = tempDir.resolve("last-run")

        assertThrows(IllegalArgumentException::class.java) {
            RunIdDirectory(root).record(DefinitionId("MyPipeline"), RunId("run-1"))
        }
        assertFalse(
            Files.exists(root),
            "a refused identity must not leave a durable location behind, not even an empty one",
        )
    }

    @Test
    fun `a refused identity is refused on the way back too`() {
        val root = tempDir.resolve("last-run")
        RunIdDirectory(root).record(DefinitionId("pipeline"), RunId("run-1"))

        // The way back is the way `--resume` reads: an identity that cannot
        // name the record cannot read one either, and it says so instead of
        // answering with the record of a neighbour.
        assertThrows(IllegalArgumentException::class.java) {
            RunIdDirectory(root).findLastRunId(DefinitionId("Pipeline"))
        }
    }
}
