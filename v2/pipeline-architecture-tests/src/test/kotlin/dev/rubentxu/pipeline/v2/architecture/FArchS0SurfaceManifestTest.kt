package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions

/**
 * S0-A2: the DSL Surface Manifest v1 machine-check (Semantic Conservation Law).
 *
 * The manifest at docs/v2/surface/DSL_SURFACE_MANIFEST.md is the closed inventory of
 * the DSL surface. This test fails when code and manifest disagree, in either
 * direction:
 *
 *  - a live public DSL builder or StepSpec subtype with no manifest row (I6: the
 *    surface grows only through the manifest);
 *  - a manifest row naming a construct that no longer exists on the live surface;
 *  - a category or state outside the closed sets (I2), or an UNKNOWN anywhere (I3);
 *  - a fail-closed stub that stopped throwing (I5);
 *  - a BLOCK_STEP manifest key with no canonical-engine descriptor row (I4);
 *  - duplicate construct rows (I1).
 *
 * Parsing is deliberately dumb and line-based: the manifest is a table, not a DSL.
 */
class FArchS0SurfaceManifestTest {

    // ------------------------------------------------------------------
    // Manifest location
    // ------------------------------------------------------------------

    private fun manifestFile(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "docs/v2/surface/DSL_SURFACE_MANIFEST.md")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("DSL_SURFACE_MANIFEST.md not found upward from ${System.getProperty("user.dir")}")
    }

    private data class Row(
        val construct: String,
        val signature: String,
        val category: String,
        val state: String,
        val interpreter: String,
    )

    private fun loadRows(): List<Row> {
        val categories = setOf(
            "DECLARATIVE_DIRECTIVE", "ATOMIC_STEP", "BLOCK_STEP",
            "PURE_BUILDER", "SCRIPTED_RUNTIME_CALL", "UNSUPPORTED_FAIL_CLOSED",
        )
        val states = setOf("STABLE", "PARTIAL", "EXPERIMENTAL", "DEPRECATED", "UNSUPPORTED_FAIL_CLOSED")

        val lines = manifestFile().readLines()
        val rows = mutableListOf<Row>()
        for ((index, line) in lines.withIndex()) {
            if (!line.startsWith("| ") || line.contains("---")) continue
            val cells = line.trim('|').split('|').map { it.trim() }
            // Header rows have exactly the fixed labels; data rows have 5 cells.
            if (cells.size != 5) continue
            if (cells[0] == "Construct") continue
            val (construct, signature, category, state, interpreter) = cells
            check(category in categories) {
                "manifest line ${index + 1}: category '$category' outside the closed set"
            }
            check(state in states) {
                "manifest line ${index + 1}: state '$state' outside the closed set"
            }
            check(!category.contains("UNKNOWN", ignoreCase = true) && !state.contains("UNKNOWN", ignoreCase = true)) {
                "manifest line ${index + 1}: UNKNOWN is not a legal category/state"
            }
            check(interpreter.isNotBlank()) {
                "manifest line ${index + 1}: interpreter column must not be empty"
            }
            rows.add(Row(construct, signature, category, state, interpreter))
        }
        check(rows.isNotEmpty()) { "manifest parsed to zero rows - table format drifted?" }
        return rows
    }

    // ------------------------------------------------------------------
    // Live surface (reflection over the scripting-api classes)
    // ------------------------------------------------------------------

    private val dslClasses = listOf(
        "dev.rubentxu.pipeline.v2.dsl.StageScope",
        "dev.rubentxu.pipeline.v2.dsl.StageScopeTopSteps",
        "dev.rubentxu.pipeline.v2.dsl.StageScopeCore",
        "dev.rubentxu.pipeline.v2.dsl.PipelineScope",
        "dev.rubentxu.pipeline.v2.dsl.StagesScope",
    ).map { Class.forName(it).kotlin }

    /** Top-level entry points that are not member functions of any scope. */
    private val topLevelBuilders = setOf("pipeline")

    /** Builders that are Kotlin language surface, not DSL constructs. */
    private val ignoredFunctions = setOf(
        "steps", "buildStages", "buildStageBuilders", "build", "toStageBuilder",
        "equals", "hashCode", "toString", "copy",
    )

    private fun liveBuilderNames(): Set<String> =
        dslClasses.flatMap { cls ->
            cls.memberFunctions
                .filter { it.visibility == KVisibility.PUBLIC }
                .filter { it.name !in ignoredFunctions }
                .map { it.name }
        }.toSet() + topLevelBuilders

    // ------------------------------------------------------------------
    // Live StepSpec family
    // ------------------------------------------------------------------

    private val stepSpecSubtypes: Set<String> = StepSpec::class.sealedSubclasses
        .map { it.simpleName!! }
        .toSet()

    /**
     * StepSpec subtypes produced by named DSL builders that encode payloads inline
     * (registry encoders). Their builder rows are the manifest authority; the subtype
     * itself is IR detail shared by several rows.
     */
    private val inlineEncodedBuilders = mapOf(
        "RegistryStepSpec" to listOf(
            "cleanWs", "milestone", "stash", "unstash", "publishHTML",
            "artifactQuery", "registryStep", "pwd", "isUnix", "load",
        ),
        "RegistryBlockSpec" to listOf("registryBlock"),
    )

    // ------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------

    @Test
    fun `every manifest row has a legal category state and interpreter`() {
        // Enforced during parsing; this test makes the law explicit and fails
        // with the parse diagnostics if anything drifted.
        val rows = loadRows()
        check(rows.all { it.category.isNotEmpty() && it.state.isNotEmpty() })
    }

    @Test
    fun `manifest constructs are unique per name and category`() {
        val rows = loadRows()
        val seen = mutableSetOf<String>()
        for (row in rows) {
            check(seen.add("${row.construct}::${row.category}")) {
                "duplicate manifest row: ${row.construct} (${row.category})"
            }
        }
    }

    @Test
    fun `every public DSL builder has a manifest row`() {
        val manifestNames = loadRows().map { it.construct }.toSet()
        val unlisted = liveBuilderNames() - manifestNames
        check(unlisted.isEmpty()) {
            "DSL builders without a manifest row (I6 - add them to DSL_SURFACE_MANIFEST.md): $unlisted"
        }
    }

    @Test
    fun `every manifest construct exists on the live DSL surface`() {
        val live = liveBuilderNames()
        // Skeleton rows live on PipelineScope/StagesScope (also in liveBuilderNames), so
        // EVERY StageScope-member row must resolve to a live function. Allowlist rows:
        //  - "retry conditions": documents a REMOVED overload (no live member by design);
        //  - "retry (retrofit)": the fail-closed stub shares the live block `retry` name,
        //    so it is matched by the base name.
        val staleRowAllowlist = setOf("retry conditions")
        val nameNormalization = mapOf("retry (retrofit)" to "retry")
        val missing = loadRows()
            .map { it.construct }
            .filter { it !in staleRowAllowlist }
            .map { nameNormalization[it] ?: it }
            .filter { it !in live }
        check(missing.isEmpty()) {
            "manifest rows naming constructs that do not exist on the live DSL surface: $missing"
        }
    }

    @Test
    fun `every StepSpec subtype is covered by the manifest or an inline encoder`() {
        val manifestNames = loadRows().map { it.construct }.toSet()
        val coveredByInline = inlineEncodedBuilders.keys +
            inlineEncodedBuilders.values.flatten()
        val directBuilders = setOf(
            // builder name -> subtype it produces
            "echo" to "Echo",
            "sh" to "Shell",
            "error" to "Error",
            "sleep" to "Sleep",
            "parallel" to "Parallel",
            "writeFile" to "WriteFile",
            "readFile" to "ReadFile",
            "fileExists" to "FileExists",
            "deleteDir" to "DeleteDir",
            "dir" to "Dir",
            "withEnv" to "WithEnv",
            "archiveArtifacts" to "ArchiveArtifacts",
            "withCredentials" to "WithCredentialsBlock",
            "checkout" to "Checkout",
            "catchError" to "CatchError",
            "warnError" to "WarnError",
            "unstable" to "Unstable",
            "load" to "Load",
            "waitUntil" to "WaitUntilBlock",
            "timestamps" to "Timestamps",
            "ansiColor" to "AnsiColor",
            "node" to "NodeNoOp",
            "timeout" to "TimeoutBlock",
            "retry" to "RetryBlock",
            "cleanWs" to "CleanWs",
        )
        val unexplained = stepSpecSubtypes.filter { subtype ->
            val hasDirectBuilder = directBuilders.any { it.second == subtype && it.first in manifestNames }
            val hasInline = subtype in coveredByInline
            // BranchSpec/CredentialsBinding are payload fragments (no builder). IsUnix is the
            // scripted-runtime Boolean carrier. ArtifactQuery/Milestone/Pwd are LEGACY sealed
            // members with NO DSL producer left (their builders lower to RegistryStepSpec);
            // the compiler keeps encodePayload branches only for the legacy envelope contract.
            val isInternal = subtype in setOf(
                "BranchSpec", "CredentialsBinding", "IsUnix",
                "ArtifactQuery", "Milestone", "Pwd",
            )
            !hasDirectBuilder && !hasInline && !isInternal
        }
        check(unexplained.isEmpty()) {
            "StepSpec subtypes with no manifest-backed producer: $unexplained"
        }
    }

    @Test
    fun `fail-closed stubs still throw at call time`() {
        // The manifest pins three throwing stubs on the live StageScope surface.
        // If any of them stopped throwing (or was silently turned into a no-op),
        // the surface would accept a construct whose semantics nobody implements.
        val throwing = listOf(
            Triple("agent", arrayOf("linux"), 2),
            Triple("whenCondition", arrayOf("1 == 2", Any()), 2),
        )
        val scope = dev.rubentxu.pipeline.v2.dsl.StageScope("manifest-probe")
        for ((name, args, arity) in throwing) {
            val fn = dslClasses.firstNotNullOfOrNull { cls ->
                cls.memberFunctions.firstOrNull {
                    it.name == name && it.parameters.size == arity + 1
                }
            } ?: error("stub '$name' vanished from the DSL surface - update the manifest")
            val ex = try {
                fn.call(scope, *args)
                null
            } catch (expected: Exception) {
                expected
            }
            check(ex != null) {
                "fail-closed stub '$name' no longer throws - the manifest row is now a lie"
            }
            // unwrap InvocationTargetException to reach the real diagnostic
            val cause = (ex as? java.lang.reflect.InvocationTargetException)?.targetException ?: ex
            check(cause is RuntimeException) {
                "stub '$name' must fail with a RuntimeException diagnostic, got: $cause"
            }
        }
        // The step-level retrofit retry(count, delaySeconds) stub shares its name with
        // the real block retry(count) { }: pinned by reflection in RetryConditionsFailClosedTest
        // (no List-taking overload) and StepSpecRetryCapabilityTest (throws with the
        // removed-consumer diagnostic). Duplicating that here would race the overload
        // resolution, so the manifest check relies on those two dedicated pins.
    }

    @Test
    fun `block step manifest keys resolve to canonical engine body rows`() {
        val registry = StepDescriptorRegistry.standard()
        val canonicalBodyKeys = registry.bodyStepIds(BodyExecutionOwner.CANONICAL_ENGINE).map { it.value }.toSet()
        val blockRows = loadRows().filter { it.category == "BLOCK_STEP" && it.state == "STABLE" }

        val expectedKeys = mapOf(
            "dir" to "core.dir",
            "withEnv" to "core.withEnv",
            "withCredentials" to "core.withCredentials",
            "timestamps" to "core.timestamps",
            "timeout" to "core.timeout",
            "retry" to "core.retry",
            "waitUntil" to "core.waitUntil",
        )
        for (row in blockRows) {
            val key = expectedKeys[row.construct] ?: continue
            check(key in canonicalBodyKeys) {
                "manifest marks '$row.construct' STABLE BLOCK_STEP but '$key' has no " +
                    "CANONICAL_ENGINE body row (interpreter deleted?)"
            }
        }
    }

    @Test
    fun `atomic step manifest keys resolve to registered core handlers`() {
        val registered = dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
            .registry().keys().map { it.value }.toSet()
        val expectedKeys = mapOf(
            "echo" to "core.echo",
            "sh" to "core.sh",
            "sleep" to "core.sleep",
            "writeFile" to "core.file.writeFile",
            "readFile" to "core.readFile",
            "fileExists" to "core.fileExists",
            "deleteDir" to "core.deleteDir",
            "cleanWs" to "core.cleanWs",
            "archiveArtifacts" to "core.archiveArtifacts",
            "artifactQuery" to "core.artifact.query",
            "milestone" to "core.milestone",
            "stash" to "core.stash",
            "unstash" to "core.unstash",
            "publishHTML" to "core.publishHTML",
        )
        val atomicStable = loadRows().filter { it.category == "ATOMIC_STEP" && it.state == "STABLE" }
        for (row in atomicStable) {
            val key = expectedKeys[row.construct] ?: continue
            check(key in registered) {
                "manifest marks '${row.construct}' STABLE ATOMIC_STEP but '$key' is not " +
                    "registered in CoreStepRegistryFactory (interpreter deleted?)"
            }
        }
    }
}
