package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * TRAIN H4 / PR-020 — the shared-model COMPOSITION ledger.
 *
 * PR-020 names its real defect "semantic cherry-pick": a change is taken from one context and
 * applied where it does not compose, and the repository has nothing that notices. The usual
 * victims are the shared domain model — the types with enough fan-out that a change to one
 * reaches across module boundaries.
 *
 * This gate does not try to detect a cherry-pick semantically. It does the narrower and
 * mechanically decidable thing: it PINS which modules consume each shared type, and fails when
 * that set changes without a deliberate edit here. A type that silently acquires a new consumer
 * module, or silently loses one, or moves out of the model, breaks the build.
 *
 * ## Why the ledger and not a blanket "no else" scan
 *
 * A tempting version of this rule is "a shared ADT must never be matched with `else`". Measured
 * on this repository, production code already carries 145 `else ->` branches, so that rule
 * would fire on unrelated code and train everyone to ignore it. A ledger only speaks about the
 * types it names, which is what makes its failures worth reading.
 *
 * ## Why the ledger is not a freeze
 *
 * The declared sets are the MEASURED composition, not a ceiling. Adding a consumer is a normal
 * consequence of extracting code into another module — which is exactly what PR-020's own
 * coordinator collapse does. The obligation is that the change and the ledger edit land
 * together, so a reader of the diff sees the coupling being acknowledged rather than accrued.
 *
 * ## What this gate does not see
 *
 * It does not see a change of behaviour inside a declared consumer, a new type in the model
 * that nobody declared here, or fan-out at FILE granularity within a module. It is a coupling
 * ledger, not a semantic analyser. A shape it cannot decide should extend this file rather than
 * be assumed covered.
 */
class SharedModelCompositionFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    /**
     * The shared model, as measured. Every entry is a type declared in a module that at least one
     * other module names, which is the definition of "shared" this repository operates on.
     */
    private data class SharedType(
        val name: String,
        val declaringModule: String,
        val consumerModules: Set<String>,
    )

    private val sharedModel = listOf(
        SharedType("ReplayPolicy", "pipeline-domain", setOf("pipeline-application")),
        SharedType("OperationStatus", "pipeline-domain", setOf("pipeline-application", "pipeline-events")),
        SharedType("BlockSegment", "pipeline-domain", setOf("pipeline-application")),
        SharedType("RecoveryPolicy", "pipeline-domain", setOf("pipeline-application")),
        SharedType("BodyExecutionPolicy", "pipeline-domain", setOf("pipeline-application")),
    )

    private fun sourcesOf(module: String): List<Path> {
        val root = v2Root.resolve(module).resolve("src/main/kotlin")
        require(Files.exists(root)) { "Expected module sources not found: $root" }
        return Files.walk(root).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.toList()
        }
    }

    private fun consumersOf(typeName: String): Set<String> =
        v2Root.resolve(".").let { root ->
            val modules = Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it.resolve("src/main/kotlin")) }
                    .map { it.fileName.toString() }
                    .toList()
            }
            modules.filter { module ->
                sourcesOf(module).any { Files.readString(it).contains(typeName) }
            }.toSet()
        }

    /**
     * The composition itself. A type gaining or losing a consumer module, or leaving the model
     * entirely, is a change to the coupling graph and must be acknowledged here in the same
     * change that causes it.
     */
    @Test
    fun `the measured composition of every shared type matches the ledger`() {
        val drift = sharedModel.mapNotNull { declared ->
            val actual = consumersOf(declared.name)
            val expected = declared.consumerModules + declared.declaringModule
            if (actual != expected) {
                "  ${declared.name}: ledger says ${expected.sorted()}, measured ${actual.sorted()}"
            } else {
                null
            }
        }

        assertEquals(
            emptyList<String>(),
            drift,
            "The shared model's composition changed. Either the change and this ledger edit belong " +
                "in the same commit, or the type is no longer shared:\n${drift.joinToString("\n")}",
        )
    }

    /**
     * Every declared type must still exist. A renamed or deleted shared type would otherwise
     * leave a ledger entry that measures nothing while still looking like coverage.
     */
    @Test
    fun `every declared shared type still exists in its declaring module`() {
        val missing = sharedModel.filterNot { declared ->
            sourcesOf(declared.declaringModule).any {
                val text = Files.readString(it)
                listOf("class", "interface", "enum class", "object").any { keyword ->
                    text.contains("$keyword ${declared.name}")
                }
            }
        }.map { "${it.name} (expected in ${it.declaringModule})" }

        assertEquals(
            emptyList<String>(),
            missing,
            "A declared shared type no longer exists in its declaring module. Remove it from the " +
                "ledger; an entry that measures nothing is worse than no entry. Missing: $missing",
        )
    }

    /**
     * The ledger must not be a decoration: each declared type must really be consumed from
     * outside its own module, otherwise it was never shared and its entry is noise.
     */
    @Test
    fun `every declared shared type is genuinely consumed from another module`() {
        val selfOnly = sharedModel.filter { declared ->
            declared.consumerModules.none { it != declared.declaringModule }
        }.map { it.name }

        assertTrue(
            selfOnly.isEmpty(),
            "These ledger entries name a type no other module consumes, so they are not shared " +
                "model and their entries are noise: $selfOnly",
        )
    }
}
