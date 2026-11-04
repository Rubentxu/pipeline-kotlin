package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.NullEventSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The naming border of the publish adapter.
 *
 * The guarantee: a run identity that cannot name the reports root is refused,
 * with the reason stated, and leaves no durable location behind — while a run
 * identity that can name it publishes as it always did.
 *
 * The reports root is named after the run, so a name that cannot carry the run
 * would put two runs in one directory: on a volume that folds case — APFS by
 * default, exFAT — the same identity in upper case and in lower case are one
 * directory, and the reader
 * of one would be handed the reports of the other. The identity is refused at
 * the border instead, because a durable name that is not the identity is a
 * folded name.
 *
 * The second test is the other side of the same discrimination: a guard that
 * refused every identity would satisfy the first one and break the product.
 */
class PublishHtmlOperationsAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun adapter(workspace: Path, controlRoot: Path, runId: String = "run-1") =
        PublishHtmlOperationsAdapter(
            runIdString = runId,
            stageIdentity = StageIdentity(name = "stage-a", index = 0),
            controlDirRoot = controlRoot,
            eventSink = NullEventSink,
            executionLocation = ExecutionLocation(
                workspace = WorkspaceLease.Managed(workspace),
                cwd = workspace,
            ),
        )

    @Test
    fun `refuses a publish whose run identity cannot name the reports root`() {
        val workspace = tempDir.resolve("ws")
        Files.createDirectories(workspace.resolve("reports"))
        Files.writeString(workspace.resolve("reports/page.html"), "<html></html>")
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot)

        val published = adapter(workspace, controlRoot, runId = "RUN-1")
            .publish(PublishHtmlInput(name = "report", reportDir = "reports", reportFiles = "*.html"))

        val failure = assertInstanceOf(PublishHtmlFailed::class.java, published)
        assertEquals(FailureKind.USER, failure.failureKind)
        assertTrue(
            failure.message.contains("cannot name a durable location"),
            "the refusal must name the reason it refuses, was: ${failure.message}",
        )
        assertFalse(
            Files.exists(controlRoot.resolve("reports")),
            "a refused identity must not leave a durable location behind",
        )
    }

    @Test
    fun `publishes as usual when the run identity can name the reports root`() {
        val workspace = tempDir.resolve("ws")
        Files.createDirectories(workspace.resolve("reports"))
        Files.writeString(workspace.resolve("reports/page.html"), "<html>pagina</html>")
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot)

        val published = adapter(workspace, controlRoot, runId = "run-1")
            .publish(PublishHtmlInput(name = "report", reportDir = "reports", reportFiles = "*.html"))

        assertInstanceOf(PublishHtmlPublished::class.java, published)
        assertEquals(
            "<html>pagina</html>",
            Files.readString(controlRoot.resolve("reports/run-1/report/page.html")),
            "the published archive must carry the file bytes",
        )
    }
}
