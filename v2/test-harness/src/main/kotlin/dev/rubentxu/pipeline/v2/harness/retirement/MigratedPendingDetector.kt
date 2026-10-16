package dev.rubentxu.pipeline.v2.harness.retirement

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Detects whether a test file has been transformed to the
 * migrated-pending state per
 * `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md`.
 *
 * The transformation is a JUnit `@Disabled("migrated-pending")`
 * annotation on the class declaration, accompanied by a
 * provenance comment. This is the legitimate state for a UAT that
 * awaits equivalence proof from the harness. The inventory
 * classifier consults this detector so a fresh
 * `inventory_tests.py` run reflects the actual state of the
 * source, not just the filename heuristic.
 *
 * Reading the file IS I/O. The decider is pure over the bytes; the
 * shell (the Gradle task / the CLI) is the one that opens the file.
 * We do not call `Files.read*` from [TestClassifier].
 */
object MigratedPendingDetector {
    private const val DISABLED_ANNOTATION = "@Disabled(\"migrated-pending\")"
    private const val PROVENANCE_MARKER = "Migrated-pending per"

    /**
     * True iff the file content has both the
     * `@Disabled("migrated-pending")` annotation and the
     * provenance comment that the migration script emits.
     */
    fun isMigratedPending(path: Path): Boolean = try {
        val text = Files.readString(path)
        text.contains(DISABLED_ANNOTATION) && text.contains(PROVENANCE_MARKER)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Pure variant: same check over a pre-read content string.
     * Useful for tests and for the inventory adapter when the
     * caller has already buffered the bytes.
     */
    fun isMigratedPendingText(text: String): Boolean =
        text.contains(DISABLED_ANNOTATION) && text.contains(PROVENANCE_MARKER)
}
