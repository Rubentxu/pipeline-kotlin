package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.valueParameters

/**
 * S3.0 — the PURE_BUILDER half of the Semantic Conservation Law, made observable.
 *
 * Law 3 says a configuration builder "MUST NOT append a Step, emit an event,
 * acquire a capability or perform I/O", and the previous session recorded that
 * only the consumption half had a test. This file closes the rest, and it closes
 * it in the only way that does not rot: by making the claim STRUCTURAL rather
 * than by enumerating the builders that happen to be pure today.
 *
 * The load-bearing observation is a module fact. Every PURE_BUILDER the manifest
 * can name is declared in `pipeline-scripting-api`, and that module's only
 * compile dependency is `pipeline-domain`. It therefore has no `EventSink`, no
 * capability access, no journal and no process/network adapter ON ITS CLASSPATH
 * AT ALL. A builder living there cannot emit an event or acquire a capability,
 * because there is nothing to call. That is worth more than a per-method
 * signature check, which would only re-prove the same thing for the methods
 * somebody remembered to write down.
 *
 * So this fitness has three layers, deliberately ordered from strongest to most
 * local:
 *
 *  1. CONTAINMENT — the DSL module cannot reach effect machinery at all.
 *  2. SIGNATURE  — no PURE_BUILDER declares or returns an effect type, which
 *     keeps the guarantee honest if the module ever gains a dependency.
 *  3. NON-VACUITY — the manifest still has PURE_BUILDER rows, so a future
 *     reclassification cannot quietly turn this fitness into a test of nothing.
 *
 * The runtime half of the same law (a pure builder contributes zero events to a
 * real installed run) is NOT asserted here: that needs the installed
 * distribution, so it lives with its probe in `pipeline-application` as
 * `FArchS3PureBuilderPurityWitnessTest`. Splitting it this way is the point —
 * this file runs everywhere, including where no host is available, and it does
 * not pretend to cover what it cannot.
 */
class FArchS3PureBuilderPurityTest {

    // ------------------------------------------------------------------
    // The effect vocabulary a PURE_BUILDER must never be able to touch
    // ------------------------------------------------------------------

    /**
     * Import prefixes that would make "emits no event" / "acquires no
     * capability" false for EVERY builder in the module, not just one.
     *
     * `dev.rubentxu.pipeline.v2.dsl` and `...domain` are absent on purpose: they
     * are the module's own layers. The forbidden list is effect machinery only.
     */
    private val effectImportPrefixes = listOf(
        // Runtime events: EventSink, JsonEventLog, every DomainEvent.
        "dev.rubentxu.pipeline.v2.events",
        // Capability admission: anything that hands a handler a capability.
        "dev.rubentxu.pipeline.v2.capabilit",
        "dev.rubentxu.pipeline.v2.domain.capabilit",
        // Persistence / journal / artefact stores.
        "dev.rubentxu.pipeline.v2.artefact",
        "dev.rubentxu.pipeline.v2.credentials",
        "dev.rubentxu.pipeline.v2.domain.durable",
        // Step SDK and the canonical runtime: handlers, capabilities, engines.
        "dev.rubentxu.pipeline.v2.sdk",
        "dev.rubentxu.pipeline.v2.application",
        // Process execution.
        "java.lang.ProcessBuilder",
        // Network. No trailing dot: SourceScanner matches `fqcn == prefix ||
        // fqcn.startsWith("$prefix.")`, so a "java.net." prefix would be tested
        // as "java.net.." and could never match anything.
        "java.net",
        // Mutation-capable IO and process launch.
        "java.io.FileOutputStream",
        "java.io.FileWriter",
        "java.io.RandomAccessFile",
        "java.nio.channels",
        "java.lang.Runtime",
    )

    /**
     * Simple names that mark a type as effect-bearing, used by the signature
     * layer. Matching on the simple name is coarse on purpose: a false positive
     * costs one builder's worth of investigation, while a missed rename would
     * cost the whole law.
     */
    private val effectTypeNameFragments = listOf(
        "EventSink",
        "EventStore",
        "EventJournal",
        "OperationJournal",
        "CapabilityAccess",
        "StepCapability",
        "CapabilityGrant",
        "CanonicalRuntime",
        "ProcessRunner",
        "ProcessEngine",
        "FileSystem",
        "FileWriter",
        "FileOutputStream",
        "HttpClient",
        "Socket",
        "SecretStore",
        "CredentialProvider",
    )

    // ------------------------------------------------------------------
    // 1. CONTAINMENT — the structural half of the law
    // ------------------------------------------------------------------

    @Test
    fun `the DSL module cannot import effect machinery`() {
        val root = ScannerSupport.v2Root()
        val dslSrc = root.resolve("pipeline-scripting-api/src/main")
        check(dslSrc.toFile().exists()) {
            "pipeline-scripting-api/src/main not found under ${root}; the containment rule " +
                "would be vacuous if it silently scanned nothing"
        }

        val findings = ScannerSupport.findForbiddenImportPrefixes(dslSrc, effectImportPrefixes)
        assertTrue(
            findings.isEmpty(),
            "Semantic Constitution law 3: a PURE_BUILDER must not be able to emit an event or " +
                "acquire a capability. pipeline-scripting-api hosts every PURE_BUILDER the " +
                "manifest can name, so an effect import there makes the law false for all of " +
                "them at once. Offending imports: $findings",
        )
    }

    @Test
    fun `the DSL module declares no effect-bearing dependency`() {
        val root = ScannerSupport.v2Root()
        val buildFile = root.resolve("pipeline-scripting-api/build.gradle.kts")
        check(buildFile.toFile().exists()) {
            "pipeline-scripting-api/build.gradle.kts not found under $root"
        }

        // The build file names the classpath the containment test reasons about.
        // Asserting the ONE dependency that is legitimate keeps the rule honest:
        // without this, someone could satisfy the import scan by depending on
        // pipeline-events without importing it yet.
        val text = buildFile.toFile().readText()
        val projectDeps = Regex("""project\(\s*"[:]([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()

        val forbiddenDeps = setOf(
            "pipeline-events",
            "pipeline-application",
            "pipeline-artefacts-local",
            "pipeline-credentials-api",
            "pipeline-credentials-local",
            "pipeline-credentials-executor",
            "pipeline-step-sdk:api",
            "pipeline-step-sdk:runtime",
        )
        val offenders = projectDeps intersect forbiddenDeps
        assertTrue(
            offenders.isEmpty(),
            "pipeline-scripting-api must depend only on :pipeline-domain, but declares " +
                "$offenders. A PURE_BUILDER can only be pure if the module that hosts it " +
                "cannot reach an event sink, a capability or a process adapter.",
        )
        assertTrue(
            projectDeps.contains("pipeline-domain"),
            "expected :pipeline-domain among the DSL module dependencies, found $projectDeps; " +
                "if the module was split or renamed this fitness is no longer proving anything",
        )
    }

    // ------------------------------------------------------------------
    // 2. SIGNATURE — defence in depth, manifest-driven
    // ------------------------------------------------------------------

    @Test
    fun `no PURE_BUILDER declares or returns an effect-bearing type`() {
        val rows = pureBuilderRows()
        for ((construct, fn) in rows) {
            val offending = fn.valueParameters
                .map { it.type.classifier?.toString() ?: it.type.toString() } +
                listOfNotNull(fn.returnType.classifier?.toString())

            for (type in offending) {
                val hit = effectTypeNameFragments.firstOrNull { type.contains(it) }
                check(hit == null) {
                    "manifest classifies '$construct' as PURE_BUILDER, but its signature mentions " +
                        "the effect-bearing type fragment '$hit' ($type). Law 3 says a pure " +
                        "builder emits no event and acquires no capability; a signature that " +
                        "names one of those types contradicts the classification."
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. NON-VACUITY — the fitness must keep having a subject
    // ------------------------------------------------------------------

    @Test
    fun `the manifest still declares PURE_BUILDER rows with live builders`() {
        val rows = pureBuilderRows()
        assertTrue(
            rows.isNotEmpty(),
            "no manifest PURE_BUILDER row resolved to a live DSL function; this fitness would " +
                "otherwise pass over an empty set and prove nothing",
        )
    }

    /**
     * The pure-builder rows, each paired with the live function it must resolve to.
     *
     * Driven entirely by the manifest's `Construct` and `Category` columns, so a
     * builder that is reclassified stops being checked here the moment its row
     * stops saying PURE_BUILDER — and the manifest fitness fails if it is
     * reclassified without a row. There is no hardcoded list of pure builders
     * here, which is what lets a future PURE_BUILDER inherit the law.
     */
    private fun pureBuilderRows(): List<Pair<String, kotlin.reflect.KFunction<*>>> {
        val byName = dslClasses
            .flatMap { cls ->
                cls.memberFunctions
                    .filter { it.visibility == KVisibility.PUBLIC }
                    .filter { it.name !in ignoredFunctions }
            }
            .associateBy { it.name }

        return loadRows()
            .filter { it.category == "PURE_BUILDER" }
            .mapNotNull { row ->
                byName[row.construct]?.let { row.construct to it }
            }
    }

    // ------------------------------------------------------------------
    // Manifest access (deliberately dumb: the manifest is a table)
    // ------------------------------------------------------------------

    private data class Row(val construct: String, val category: String)

    private fun manifestFile(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "docs/v2/surface/DSL_SURFACE_MANIFEST.md")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("DSL_SURFACE_MANIFEST.md not found upward from ${System.getProperty("user.dir")}")
    }

    private fun loadRows(): List<Row> =
        manifestFile().readLines()
            .filter { it.startsWith("| ") && !it.contains("---") }
            .map { it.trim('|').split('|').map { c -> c.trim() } }
            .filter { it.size == 6 && it[0] != "Construct" }
            .map { Row(construct = it[0], category = it[2]) }

    private val dslClasses = listOf(
        "dev.rubentxu.pipeline.v2.dsl.StageScope",
        "dev.rubentxu.pipeline.v2.dsl.StageScopeTopSteps",
        "dev.rubentxu.pipeline.v2.dsl.StageScopeCore",
        "dev.rubentxu.pipeline.v2.dsl.PipelineScope",
        "dev.rubentxu.pipeline.v2.dsl.StagesScope",
    ).map { Class.forName(it).kotlin }

    private val ignoredFunctions = setOf(
        "steps", "buildStages", "buildStageBuilders", "build", "toStageBuilder",
        "equals", "hashCode", "toString", "copy",
    )

    /**
     * Proves the containment scanner can actually fail.
     *
     * A fitness that only ever sees a clean tree is indistinguishable from a
     * fitness that scans the wrong path, reads the wrong column, or matches
     * nothing at all. Each rule here is paired with a synthetic violation so the
     * green above is a measurement rather than an assumption.
     */
    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `containment scanner rejects a synthetic effect import in the DSL module`() {
            val dslSrc = tempDir.resolve("pipeline-scripting-api/src/main")
            dslSrc.toFile().mkdirs()
            dslSrc.resolve("Impure.kt").toFile().writeText(
                "package dev.rubentxu.pipeline.v2.dsl\n" +
                    "import dev.rubentxu.pipeline.v2.events.EventSink\n" +
                    "class Impure(private val sink: EventSink)\n",
            )

            val findings = ScannerSupport.findForbiddenImportPrefixes(dslSrc, effectImportPrefixes)
            assertTrue(
                findings.isNotEmpty(),
                "the containment scanner must reject an EventSink import inside the DSL module; " +
                    "if it finds nothing here it would also find nothing in the real module and " +
                    "law 3 would be unproven",
            )
        }

        @Test
        fun `containment scanner rejects a synthetic network import in the DSL module`() {
            val dslSrc = tempDir.resolve("pipeline-scripting-api/src/main")
            dslSrc.toFile().mkdirs()
            dslSrc.resolve("Dials.kt").toFile().writeText(
                "package dev.rubentxu.pipeline.v2.dsl\n" +
                    "import java.net.Socket\n" +
                    "class Dials(private val s: Socket)\n",
            )

            val findings = ScannerSupport.findForbiddenImportPrefixes(dslSrc, effectImportPrefixes)
            assertTrue(
                findings.isNotEmpty(),
                "the containment scanner must reject a java.net import inside the DSL module",
            )
        }
    }
}
