package dev.rubentxu.pipeline.v2.artefacts.local

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What the archive does with a file whose NAME looks like an option.
 *
 * ## The guarantee
 *
 * A file in the workspace is archived as a file. Its name is a name: it is
 * never read as an option of the tool that archives it, and nothing about it
 * is ever run.
 *
 * ## Why this instrument EXECUTES the archive
 *
 * The defect is not that the name reaches the wrong place: it is that the name
 * reaches a place where a dash-prefixed word IS an option. No assertion over
 * an argument list can prove that, because the argument list is only the
 * intention; what matters is what the tool DOES with it. So the instrument
 * puts a file in the workspace whose name is an option of the archiver, and
 * then watches whether the archiver obeyed the name or obeyed the option.
 *
 * The name is `--checkpoint-action=exec=false`: a checkpoint action that runs
 * `false` and fails. GNU tar runs a checkpoint action every ten records, so
 * the archive holds more files than that and the action is reached. A tar that
 * reads the name as a name archives it; a tar that reads the name as an
 * option arms the action, runs `false` at the tenth record and ends the
 * archive with a failure.
 *
 * ## What is NOT here
 *
 * The archive's contents, its digest and its permissions are the subject of
 * the module's own rows. This one is about the NAME.
 */
class LocalArtifactStoreArchiveTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a file whose name looks like an option is archived, and nothing is run`() {
        val workspace = Files.createDirectories(root.resolve("workspace"))
        // More files than a checkpoint interval, so an archiver that reads the
        // name as an option reaches its checkpoint and runs it.
        repeat(12) { index ->
            Files.writeString(workspace.resolve("file-$index.txt"), "content $index")
        }
        val optionLookingName = "--checkpoint-action=exec=false"
        Files.writeString(workspace.resolve(optionLookingName), "content")

        val store = LocalArtifactStore(root.resolve("control"))

        val archived = runCatching {
            store.archive(
                runId = RunId("run-1"),
                stageName = StageName("stage"),
                workspace = workspace,
                pattern = "**/*",
                allowEmptyArchive = false,
            )
        }

        assertTrue(
            archived.isSuccess,
            "the archiver read a file's NAME as an option of its own: " +
                "${archived.exceptionOrNull()?.message}",
        )

        val entries = archived.getOrThrow().entries
        assertTrue(
            entries.any { entry -> entry.relPath == optionLookingName },
            "the file whose name looks like an option is not in the archive: " +
                "${entries.map { entry -> entry.relPath }}",
        )
    }
}
