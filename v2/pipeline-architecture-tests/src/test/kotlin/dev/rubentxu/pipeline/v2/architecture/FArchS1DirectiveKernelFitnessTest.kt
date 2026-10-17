package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * S1 — Directive Kernel fitness.
 *
 * The S1 exit criterion is "directive engine is open by key but closed by
 * structural policy". That is a claim about SOURCE SHAPE, so it is checked at
 * source level, exactly as the Step Constitution fitness does for Steps.
 *
 * The three laws enforced here, in order of how badly their violation would
 * hurt:
 *
 *  1. NO CONCRETE-KEY BRANCHING in the kernel. A `when` over a directive name,
 *     or a set of known names used to decide behaviour, re-closes the world the
 *     registry just opened and makes every future directive an engine change.
 *  2. KERNEL STAYS INNER. The domain contract must not import the compiler,
 *     application, or any adapter. Hexagonal direction.
 *  3. NO UNLAWFUL ERASURE. The kernel may not pass an `Any?` around; the only
 *     sanctioned erasure is the named `DirectiveDefinitionAny` boundary.
 *
 * Every rule has a ViolationFixture that feeds it a deliberately broken source
 * and asserts the scanner CATCHES it. A fitness test that cannot fail is a
 * fitness test that proves nothing, so the negative rows are the point.
 */
class FArchS1DirectiveKernelFitnessTest {

    /**
     * True when [path] lives in a TEST source set.
     *
     * Named by the source set, not by the literal `src/test`: the external-plugin
     * contract lane lives in `src/pluginContractTest`, and a predicate that only
     * knew `src/test` reported this fitness's OWN sanctioned lane as a production
     * leak of the external plugin. Test scope is the property
     * `name.endsWith("Test")`, which also covers every lane added later instead
     * of needing a second literal per lane.
     *
     * Safe by construction: no production source set ends in `test`.
     *
     * The comparison is case-insensitive on purpose: the default lane is
     * `src/test` (lower case) while purpose-built lanes are CamelCase
     * (`src/pluginContractTest`), so a case-sensitive `endsWith("Test")` matches
     * the specialised lanes and MISSES the default one — which is how the first
     * version of this helper flagged four `src/test` files as production leaks.
     */
    private fun isTestSourceSet(path: Path): Boolean =
        Regex("""src[/\\]([^/\\]+)[/\\]""")
            .find(path.toString())
            ?.groupValues
            ?.get(1)
            ?.endsWith("test", ignoreCase = true) == true

    /**
     * The file with comments removed.
     *
     * A reference inside a comment is documentation, not coupling: stripping is what
     * lets the scanners assert on CODE without a growing exemption list, and it cannot
     * hide a real declaration because a declaration is never inside a comment.
     */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    private fun kernelSource(): Path =
        ScannerSupport.v2Root()
            .resolve("pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/directive")

    private fun kernelSources(): List<Path> =
        ScannerSupport.walkKotlinFiles(kernelSource())
            .filter { it.toFile().isFile }

    /**
     * A `when` whose subject is a directive key or key string.
     *
     * The rule lives in SourceScanner (one scanner, many fitness suites); it is
     * deliberately narrow and matches key-shaped `when` subjects, not every
     * `when`, because the kernel legitimately matches closed ADTs such as
     * [dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy].
     */

    // --- 1. no concrete-key branching --------------------------------------

    @Test
    fun `happy path — the kernel branches on no concrete directive key`() {
        val findings = ScannerSupport.findConcreteKeyBranches(kernelSource())

        assertTrue(
            findings.isEmpty(),
            "The directive kernel must not branch on concrete directive keys: " +
                "open by key means resolution goes through the registry. Found: $findings",
        )
    }

    @Test
    fun `the kernel declares no hardcoded directive names`() {
        // A literal like "when.guard" or "acme." inside the kernel means the
        // engine learned a name. Phases and policies are structural names and
        // are legitimately spelled out, so only namespace-like literals count.
        val findings = kernelSources().filter { source ->
            Regex(""""[a-z0-9-]+\.[a-z0-9.-]+"""").findAll(source.readText())
                .any { it.value != "\"\"" }
        }

        assertTrue(
            findings.isEmpty(),
            "The kernel must not contain namespaced directive literals; it resolves by " +
                "key through the registry. Offending sources: $findings",
        )
    }

    // --- 2. kernel stays inner ---------------------------------------------

    @Test
    fun `happy path — the kernel depends on nothing outward`() {
        val findings = ScannerSupport.findForbiddenImportPrefixes(
            kernelSource(),
            forbiddenImportPrefixes,
        )

        assertTrue(
            findings.isEmpty(),
            "The directive kernel is an inner domain contract: it must not import the " +
                "compiler, the application, or any adapter. Found: $findings",
        )
    }

    // --- 4. the example plugin never leaks into production (S1-D) -----------

    /**
     * S1-D law: `example.lock` is an EXTERNAL plugin under certification. Its
     * key, contributor, or JAR appearing inside v2 production sources or build
     * files would re-close the open world by hardcoding one plugin — the exact
     * defect the registry exists to prevent. The only sanctioned v2 references
     * are in test code (the contract suite) wired via testImplementation.
     */
    /**
     * The sanctioned set for [pluginLeakOffenders].
     *
     * `walkKotlinFiles` includes `*.gradle.kts` (Kotlin scripts), so a path alone does not
     * decide guilt:
     *  - any TEST source set                     -> sanctioned (contract suites)
     *  - `pipeline-application/build.gradle.kts`  -> sanctioned (test-scope wiring)
     *  - `v2/build.gradle.kts`                   -> sanctioned ONLY as the
     *     `:buildExternalDirectivePlugin` producer task (no consumption); a dependency
     *     declaration there is still a leak, and is caught separately below
     *  - anything else                           -> production leak, violation
     *
     * Deliberately a property of the PATH, not a list of known files: a test lane added
     * tomorrow is sanctioned by being a test lane, not by having been written down here.
     */
    private fun sanctioned(path: Path): Boolean =
        isTestSourceSet(path) ||
            path.endsWith("pipeline-application${File.separatorChar}build.gradle.kts") ||
            path.endsWith("v2${File.separatorChar}build.gradle.kts")

    /**
     * Sources under [root] that reference the external example plugin without being
     * [sanctioned].
     *
     * Parameterised by root, not hard-wired to `v2/`, for one reason: the negative rows
     * must exercise THIS predicate. An inline scan reimplemented inside the fixture passes
     * while the production predicate is broken — which is exactly how `isTestSourceSet` and
     * `codeOnly` were each found defective (a case-sensitive `endsWith("Test")` that missed
     * `src/test`; a comment scanned as if it were a declaration). One scanner, many callers:
     * the happy path and the ViolationFixture below now share a single implementation, so a
     * row that goes green is evidence about the shipped rule.
     *
     * Comment-stripped, so a PROSE mention is not a reference. Without that,
     * `pipeline-release/build.gradle.kts` was flagged as a leak for a comment explaining
     * the release-gate test classpath — and the only way to silence it was a fourth literal
     * in the sanctioned list, which treats each new mention as a new exemption instead of
     * naming the property that makes it harmless.
     */
    internal fun pluginLeakOffenders(root: Path): List<Path> =
        ScannerSupport.walkKotlinFiles(root)
            .filter { it.toFile().isFile }
            .filter { path ->
                val text = codeOnly(path.toFile().readText())
                text.contains("example.lock") ||
                    text.contains("LockContributor") ||
                    text.contains("example-directive-plugin")
            }
            .filterNot(::sanctioned)

    @Test
    fun `the example directive plugin leaks nowhere into production`() {
        val offenders = pluginLeakOffenders(ScannerSupport.v2Root()).map { it.toString() }

        // The wiring in the sanctioned build files must stay consumption-free:
        // v2/build.gradle.kts may only DEFINE the producer task; a dependency
        // scope (implementation/api) on the example JAR anywhere is a leak.
        // Comment-stripped too: a commented-out dependency is not wiring.
        val dependencyScopeLeak = Regex("""(implementation|api)\([^\n]*(example-directive-plugin|example\.lock)""")
        val wiringViolations = ScannerSupport.walkBuildFiles(ScannerSupport.v2Root())
            .filter { codeOnly(it.toFile().readText()).contains("example-directive-plugin") }
            .filter { codeOnly(it.toFile().readText()).let { t -> dependencyScopeLeak.containsMatchIn(t) } }

        assertTrue(
            offenders.isEmpty() && wiringViolations.isEmpty(),
            "example-directive-plugin (acme.lock) is an external plugin: it must never " +
                "appear in v2 production sources or as a production dependency scope. " +
                "Sources: $offenders; build files: $wiringViolations",
        )
    }

    // --- 3. no unlawful erasure --------------------------------------------

    @Test
    fun `happy path — the kernel exposes no Any-typed public surface`() {
        val offenders = kernelSources().filter { source ->
            // `Any?` / `: Any` on a public member, but NOT the sanctioned
            // DirectiveDefinitionAny boundary.
            val text = source.readText()
            Regex("""\)\s*:\s*Any\??\b|:\s*Any\?\s*[,)=]""").containsMatchIn(text)
        }

        assertEquals(
            emptyList<Path>(),
            offenders,
            "The kernel must not pass untyped payloads; the only sanctioned erasure is " +
                "the named DirectiveDefinitionAny boundary. Offenders: $offenders",
        )
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        private fun write(name: String, body: String): Path =
            tempDir.resolve(name).also {
                it.parent.createDirectories()
                it.writeText(body)
            }

        @Test
        fun `a concrete-key branch is detected`() {
            val bad = write(
                "BadKernel.kt",
                """
                package dev.rubentxu.pipeline.v2.domain.directive
                fun route(key: DirectiveKey): String = when (key.value) {
                    "acme.guard" -> "gate"
                    else -> "none"
                }
                """.trimIndent(),
            )

            val findings = ScannerSupport.findConcreteKeyBranches(bad.parent)

            assertTrue(
                findings.isNotEmpty(),
                "The fitness must catch a `when` over a concrete directive key; " +
                    "otherwise open-by-key is not actually enforced",
            )
        }

        @Test
        fun `an outward import is detected`() {
            write(
                "BadImport.kt",
                """
                package dev.rubentxu.pipeline.v2.domain.directive
                import dev.rubentxu.pipeline.v2.application.DslCompiledPipelineCompiler
                """.trimIndent(),
            )

            val findings = ScannerSupport.findForbiddenImportPrefixes(
                tempDir,
                forbiddenImportPrefixes,
            )

            assertTrue(
                findings.isNotEmpty(),
                "The fitness must catch an outward import from the kernel",
            )
        }

        @Test
        fun `a production reference to the example plugin is detected`() {
            write(
                "BadProductionLeak.kt",
                """
                package dev.rubentxu.pipeline.v2.application
                import example.lock.LockDirectiveDefinition
                fun hardcoded(): String = LockDirectiveDefinition.KEY.value
                """.trimIndent(),
            )

            val offenders = pluginLeakOffenders(tempDir)

            assertEquals(
                1,
                offenders.size,
                "The fitness must catch a hardcoded reference to the example directive " +
                    "plugin, and only that file",
            )
            assertTrue(
                offenders.single().endsWith("BadProductionLeak.kt"),
                "The scan must point at the offending file, got: $offenders",
            )
        }

        /**
         * Pins [codeOnly]. Without it this fitness reported `pipeline-release/build.gradle.kts`
         * as a production leak purely for a comment explaining the release-gate test
         * classpath — and the only escape was a fourth literal in the sanctioned list, i.e.
         * one exemption per prose mention, forever.
         */
        @Test
        fun `a COMMENTED reference to the example plugin is not a leak`() {
            write(
                "src/main/kotlin/Documented.kt",
                """
                package dev.rubentxu.pipeline.v2.application
                // The plugin JAR is example-directive-plugin; see ADR-PK-DF-002.
                /* example.lock is resolved through the registry. */
                fun documented(): String = "registry"
                """.trimIndent(),
            )

            assertEquals(
                emptyList<Path>(),
                pluginLeakOffenders(tempDir),
                "A mention inside a comment is documentation, not a reference; scanning raw " +
                    "text turns every future explanatory comment into a new exemption",
            )
        }

        /**
         * Pins [isTestSourceSet] in its ORIGINAL defect shape: the first version compared
         * case-sensitively, matched the CamelCase `src/pluginContractTest` lane and MISSED the
         * default lower-case `src/test` one — reporting four real test files as production
         * leaks. Both lanes are sanctioned here; only a production source set is not.
         */
        @Test
        fun `every TEST source set is sanctioned`() {
            write(
                "src/test/kotlin/DefaultLane.kt",
                """
                package dev.rubentxu.pipeline.v2.application
                import example.lock.LockDirectiveDefinition
                fun contract(): String = LockDirectiveDefinition.KEY.value
                """.trimIndent(),
            )
            write(
                "src/pluginContractTest/kotlin/CamelCaseLane.kt",
                """
                package dev.rubentxu.pipeline.v2.application
                import example.lock.LockDirectiveDefinition
                fun contract(): String = LockDirectiveDefinition.KEY.value
                """.trimIndent(),
            )

            assertEquals(
                emptyList<Path>(),
                pluginLeakOffenders(tempDir),
                "Test scope is the property 'the source set is a test one': `src/test` and " +
                    "`src/pluginContractTest` are both sanctioned, and neither may be " +
                    "reported as a production leak",
            )
        }
    }

    private companion object {
        val forbiddenImportPrefixes = setOf(
            "dev.rubentxu.pipeline.v2.application",
            "dev.rubentxu.pipeline.v2.dsl",
            "dev.rubentxu.pipeline.v2.sdk",
            "dev.rubentxu.pipeline.v2.scripting",
            "org.jetbrains.kotlin.scripting",
            "java.io",
            "java.nio",
            "java.sql",
            "kotlin.io",
        )
    }
}
