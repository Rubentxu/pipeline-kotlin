package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.step.AggregateDurableRole
import dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC-2 / B10 W1d — the durable aggregate identities are PINNED, not deleted.
 *
 * W1c left four items in the concrete routing ledger: one credential bypass and two
 * concrete Step identities (`core.retry`, `core.parallel`) that the coordinator used as
 * durable KEYS rather than as routing branches. The bypass was real debt and W1d burned it;
 * the identities were never routing at all.
 *
 * The dangerous way to reach zero would have been to delete the literals and call the
 * counter green: the retry control row (ADR-0075) and the parallel stage aggregate
 * (ADR-0076) are durable replay keys, and two identities that vanish from the model do not
 * stop existing — they just stop being guarded. W1d therefore RECLASSIFIES them:
 *
 * ```text
 * routing ledger          -> 0   (they are not branches)
 * BodyAggregateIdentity   -> 2   (they are durable identities, pinned here)
 * ```
 *
 * These laws make the reclassification load-bearing rather than cosmetic. Deleting a case
 * from `ALL`, renaming a key, adding a third aggregate, or re-declaring a key outside the
 * identity model each fail here.
 */
class Lfc2DurableAggregateIdentityFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private val identityModule = v2Root.resolve(
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyAggregateIdentity.kt",
    )

    private val coordinatorSource = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
    )

    /** The modules that own durable aggregate semantics. Declared boundary, not a guess. */
    private val durableSourceRoots = listOf(
        v2Root.resolve("pipeline-domain/src/main/kotlin"),
        v2Root.resolve("pipeline-application/src/main/kotlin"),
    )

    private fun read(path: Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Case names as DECLARED in the identity model. Read from source, so it needs no reflection. */
    private fun declaredCaseNames(): Set<String> =
        Regex("data object ([A-Za-z0-9_]+)\\s*:\\s*BodyAggregateIdentity")
            .findAll(read(identityModule)).map { it.groupValues[1] }.toSet()

    /** Keys as DECLARED in the identity model. */
    private fun declaredKeys(): Set<String> =
        Regex("val key:\\s*PluginStepId\\s*=\\s*PluginStepId\\(\"([^\"]+)\"\\)")
            .findAll(read(identityModule)).map { it.groupValues[1] }.toSet()

    /**
     * The anti-deletion law. Reaching a zero routing ledger by removing the two durable
     * identities is the false green W1d was chartered to prevent, so the identities are
     * asserted to still exist, still be exactly two, and still carry their replay keys.
     */
    @Test
    fun `the two durable aggregate identities are pinned, not deleted`() {
        assertEquals(
            2,
            BodyAggregateIdentity.ALL.size,
            "core.retry and core.parallel are durable replay keys (ADR-0075/ADR-0076). They " +
                "leave the routing ledger by RECLASSIFICATION; deleting them would silence the " +
                "guard instead of removing the identity",
        )
        assertEquals(
            setOf("core.retry", "core.parallel"),
            BodyAggregateIdentity.ALL.map { it.key.value }.toSet(),
            "The aggregate keys are fingerprint inputs of already-journaled rows: changing one " +
                "is a replay-breaking change, not a rename",
        )
        assertEquals(
            0,
            PinnedConcreteBodyRoutingDebt.value.total,
            "The reclassification is only honest while the ledger is genuinely empty",
        )
    }

    /** Every identity states what it is durable FOR, and that role cites its authority. */
    @Test
    fun `every aggregate identity declares a durable role with a recorded authority`() {
        val roles = BodyAggregateIdentity.ALL.map { it.durableRole }
        assertEquals(
            AggregateDurableRole.entries.toSet(),
            roles.toSet(),
            "Every declared durable role must belong to an identity, and every identity must " +
                "declare a role: a role with no identity is an unguarded durable concept",
        )
        assertTrue(
            roles.all { it.authority.startsWith("ADR-") },
            "A durable role must cite the ADR that defines it; found ${roles.map { it.authority }}",
        )
        assertTrue(
            BodyAggregateIdentity.ALL.all { it.key.value.isNotBlank() },
            "A durable key may not be blank",
        )
        assertEquals(
            BodyAggregateIdentity.ALL.size,
            BodyAggregateIdentity.ALL.map { it.key }.distinct().size,
            "Two identities sharing a key would collide in the journal",
        )
    }

    /** The coordinator names identities by type; it no longer spells their keys. */
    @Test
    fun `the coordinator reaches the identities by type and never by literal`() {
        val text = read(coordinatorSource)

        val referenced = Regex("BodyAggregateIdentity\\.([A-Za-z0-9_]+)")
            .findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(
            declaredCaseNames(),
            referenced,
            "Every durable aggregate identity must be reached by its typed name, and the " +
                "coordinator must not invent a name the identity model does not declare",
        )

        BodyAggregateIdentity.ALL.forEach { identity ->
            assertTrue(
                "\"${identity.key.value}\"" !in text,
                "The literal ${identity.key.value} must not appear in the coordinator: the " +
                    "identity model is the single place where the durable key is stated",
            )
        }
    }

    /**
     * Single authority for the DECLARATION. The scan looks for the declaration form
     * (`val key: PluginStepId = PluginStepId("core.retry")`), not for every mention of the
     * string: the aggregate key and the *Step* key `core.retry` share a name legitimately,
     * because the durable control row of a retry aggregate is identified by the retry Step's
     * key. That overlap is two namespaces agreeing on a spelling, NOT two authorities for one
     * fingerprint input.
     *
     * What must not happen is a SECOND declaration of the aggregate key: that would give the
     * fingerprint input two sources of truth.
     *
     * Boundary: the scan covers `pipeline-domain` and `pipeline-application` main sources,
     * the modules that own durable aggregate semantics. A third module is out of scope by
     * declaration, not by accident — see the coordinator law above, which covers the one
     * module that consumes these keys.
     */
    @Test
    fun `each aggregate key is declared exactly once across the durable modules`() {
        val mainKotlinFiles = durableSourceRoots
            .filter { Files.exists(it) }
            .flatMap { root -> ScannerSupport.walkKotlinFiles(root) }
            .filter { !it.toString().contains("/build/") }
            .distinct()

        val violations = BodyAggregateIdentity.ALL.flatMap { identity ->
            val declaration = "val key: PluginStepId = PluginStepId(\"${identity.key.value}\")"
            val sites = mainKotlinFiles
                .filter { declaration in Files.readString(it) }
                .map { it.toString().removePrefix("$v2Root/") }
            if (sites == listOf(identityDeclarationPath())) emptyList() else {
                listOf("${identity.key.value} declared in $sites")
            }
        }

        assertEquals(
            emptyList<String>(),
            violations,
            "A durable aggregate key must be declared once, in the identity model",
        )
    }

    /** The single declaration site: the identity model itself. */
    private fun identityDeclarationPath(): String =
        identityModule.toString().removePrefix("$v2Root/")

    /**
     * A third durable aggregate cannot appear unnoticed. The declaration site is scanned: the
     * number of leaf declarations must equal the pinned list, and every declared key must be
     * in `ALL`.
     */
    @Test
    fun `a third aggregate identity cannot be declared without being pinned`() {
        assertEquals(
            BodyAggregateIdentity.ALL.map { it.key.value }.toSet(),
            declaredKeys(),
            "The keys declared in the identity model must be exactly the pinned keys",
        )
        assertEquals(
            BodyAggregateIdentity.ALL.size,
            declaredCaseNames().size,
            "A declared aggregate identity that is missing from ALL is an unguarded durable " +
                "concept; one in ALL that is not declared cannot compile",
        )
    }
}
