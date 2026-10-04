package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BLOCK 2: what a consumer RESOLVES is the contract, and nothing else may ride along.
 *
 * ## Why this reads compiled classes and not sources
 *
 * Every other fitness in this package scans source text. That is the right instrument for a law
 * about code and the wrong one for a law about an artifact, because the artifact is what an
 * external consumer compiles against. A published jar can carry a class no `.kt` file in the
 * module mentions — a `internal` type that Kotlin still emits, a leftover from a moved file, a
 * class contributed by a plugin — and a source scan would not see any of it.
 *
 * So the first two rows read `build/classes/kotlin/main`, which is exactly what the jar is made
 * of, and ask what a consumer would find. The remaining rows cover the two things a class listing
 * cannot tell you: that the store is not published at all, and that the POM carries the
 * dependencies at a scope a consumer can actually compile against.
 *
 * ## What is deliberately not asserted
 *
 * That the contract is *small*. There is no upper bound on the number of published types here, and
 * inventing one would make this a style rule that gets renegotiated. The bound that matters is
 * directional: the contract carries no implementation, and the implementation is not published.
 */
class PublishedContractBoundaryFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    /** Modules whose contract is published, mirroring `publishedContractModules` in the root build. */
    private val publishedContractModules = listOf(
        "pipeline-domain",
        "pipeline-scripting-api",
        "pipeline-events",
        "pipeline-output",
    )

    /**
     * Packages that hold implementation and must never appear inside a published artifact.
     *
     * Named by package, not by type. A type list is a snapshot of today: a new `SqliteWhateverStore`
     * would pass a type list and ship. A package is the property itself — "durable", "store" — and a
     * class in one of them is an implementation whether or not anybody thought to write it down.
     */
    private val implementationPackages = listOf(
        "dev/rubentxu/pipeline/v2/events/durable/",
        "dev/rubentxu/pipeline/v2/output/store/",
    )

    private fun compiledClasses(module: String): Path =
        v2Root.resolve(module).resolve("build/classes/kotlin/main")

    private fun compiledClassFiles(module: String): List<Path> {
        val root = compiledClasses(module)
        assertTrue(
            Files.isDirectory(root),
            "no compiled classes at $root; this row would scan nothing and report success. " +
                "Run the module's compileKotlin first.",
        )
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.toList()
        }
    }

    @Test
    fun `no published contract carries a class from an implementation package`() {
        for (module in publishedContractModules) {
            val offenders = compiledClassFiles(module)
                .map { v2Root.resolve(module).relativize(it).toString().replace('\\', '/') }
                .filter { relative -> implementationPackages.any { relative.contains(it) } }
                .distinct()

            assertTrue(
                offenders.isEmpty(),
                ":$module is published, and these of its classes live in an implementation package. " +
                    "A consumer that resolves this artifact can name them, which makes the " +
                    "implementation part of the contract. Offenders: $offenders",
            )
        }
    }

    @Test
    fun `the durable implementation is compiled somewhere, so the first row is not vacuous`() {
        // A row that finds nothing because the subject does not exist is indistinguishable from a
        // row that passes. Each implementation module is named here and must be real, which is what
        // makes the zero in the previous row a measurement rather than an absence of measurement.
        for (module in listOf("pipeline-events-store", "pipeline-output-store")) {
            val classes = compiledClassFiles(module)
            assertTrue(
                classes.isNotEmpty(),
                ":$module has no compiled classes, so `no published contract carries a class from " +
                    "an implementation package` is currently proving nothing about it",
            )
        }
    }

    @Test
    fun `no implementation module is published`() {
        // The absence of `maven-publish` is the mechanism. BCV cannot catch this: it freezes the
        // surface of the modules it guards, and says nothing about a module that is simply shipped.
        //
        // Comments are stripped first, and here that is not a nicety. Both store build scripts
        // explain IN A COMMENT that the absence of the plugin is the mechanism, so the first
        // version of this row reported both stores as published — the fitness failing on the
        // sentence that documents the law it enforces.
        for (module in listOf("pipeline-events-store", "pipeline-output-store")) {
            val buildFile = v2Root.resolve(module).resolve("build.gradle.kts")
            assertTrue(Files.exists(buildFile), "cannot read $buildFile; this row would be vacuous")

            val text = stripComments(Files.readString(buildFile))
            assertFalse(
                Regex("""`maven-publish`|["']maven-publish["']""").containsMatchIn(text),
                ":$module declares maven-publish. That module holds the filesystem, JDBC and replay " +
                    "implementations; publishing it hands a consumer the store as part of the contract.",
            )
        }
    }

    @Test
    fun `no published contract depends on an implementation module`() {
        // The source-level half of the same law. A published module reaching `:pipeline-*-store`
        // could not compile the store's package into its consumers, but it could still name the
        // module in a comment and mislead the next reader; more importantly this catches the
        // dependency BEFORE someone moves a type across the boundary and lets the compiler decide.
        for (module in publishedContractModules) {
            val buildFile = v2Root.resolve(module).resolve("build.gradle.kts")
            if (!Files.exists(buildFile)) continue

            val text = stripComments(Files.readString(buildFile))
            val violations = Regex("""project\(\"(:pipeline-[a-z-]*(?:-store|store))\"\)""")
                .findAll(text)
                .map { it.groupValues[1] }
                .toList()

            assertTrue(
                violations.isEmpty(),
                ":$module is published and depends on an implementation module. Found: $violations",
            )
        }
    }

    @Test
    fun `a dependency that reaches the published ABI is declared api, and one that does not may not be`() {
        // `implementation` writes `runtime` scope into the POM. A consumer compiling against a
        // legal public type then fails on a type it is entitled to see, and the artifact carries no
        // evidence of why.
        //
        // The scope is not "everything is `api`". `:pipeline-domain` declares coroutines as
        // `implementation` and is right to: no coroutine type appears in its ABI. So the law is
        // stated per dependency, and each entry is cross-checked against the committed BCV dump —
        // which is what makes the row a measurement rather than a list somebody agreed to. If a
        // dependency stops reaching the ABI, the dump stops mentioning it and this row says the
        // declaration can go back to `implementation`, instead of leaving a stale `api` that reads
        // as a permanent requirement.
        //
        // The dump is read rather than the generated POM on purpose: the POM is a build output, and
        // a fitness that needs one is not hermetic.
        val abiDependencies = listOf(
            AbiDependency("pipeline-events", "api(project(\":pipeline-domain\"))", "dev/rubentxu/pipeline/v2/domain/"),
            AbiDependency("pipeline-events", "api(project(\":pipeline-scripting-api\"))", "dev/rubentxu/pipeline/v2/scripting/"),
            AbiDependency("pipeline-events", "api(libs.kotlinx.serialization.json)", "kotlinx/serialization/"),
            AbiDependency("pipeline-domain", "api(libs.kotlinx.serialization.json)", "kotlinx/serialization/"),
        )

        for (dependency in abiDependencies) {
            val buildFile = v2Root.resolve(dependency.module).resolve("build.gradle.kts")
            assertTrue(Files.exists(buildFile), "cannot read $buildFile; this row would be vacuous")

            val declaredText = stripComments(Files.readString(buildFile))
            assertTrue(
                declaredText.contains(dependency.declaration),
                ":${dependency.module} must declare ${dependency.declaration}. Its ABI dump " +
                    "references ${dependency.abiPackage}, so the POM carries that dependency at " +
                    "runtime scope and a consumer cannot compile the types it is entitled to see.",
            )

            val dump = abiDump(dependency.module)
            assertTrue(
                dump.contains(dependency.abiPackage),
                ":${dependency.module}'s ABI dump no longer references ${dependency.abiPackage}. " +
                    "Then ${dependency.declaration} is no longer required, and the declaration above " +
                    "should be revisited rather than kept as a frozen requirement nobody re-checks.",
            )
        }
    }

    /** One dependency, the declaration that publishes it, and the package proving it reaches the ABI. */
    private data class AbiDependency(val module: String, val declaration: String, val abiPackage: String)

    private fun abiDump(module: String): String {
        val dump = v2Root.resolve(module).resolve("api/$module.api")
        assertTrue(
            Files.exists(dump),
            "no ABI dump at $dump. Every module in bcvModules has one; if this module was added to " +
                "the allowlist without a dump, its surface is unguarded and this row cannot measure it.",
        )
        return Files.readString(dump)
    }

    @Test
    fun `the published contract set is the same one the build publishes`() {
        // Two lists that can drift apart are one list plus a hope. This row reads the root build's
        // `publishedContractModules` and requires this fitness to carry the same four names, so
        // adding a fifth contract without deciding whether it is guarded fails here rather than in
        // a review three weeks later.
        val rootBuild = v2Root.resolve("build.gradle.kts")
        assertTrue(Files.exists(rootBuild), "cannot read $rootBuild; this row would be vacuous")

        val declared = Regex("""val publishedContractModules = listOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(stripComments(Files.readString(rootBuild)))
            .flatMap { it.groupValues[1].lines() }
            // Match the quoted name itself. Reading the line and stripping quotes leaves the
            // trailing comma attached, and the first version of this row reported a four-element
            // list that did not equal the four-element list beside it.
            .flatMap { line -> Regex(""""([^"]+)"""").findAll(line).map { it.groupValues[1] } }
            .toList()

        assertEquals(
            publishedContractModules,
            declared,
            "the build's publishedContractModules and this fitness disagree. Whichever list is " +
                "right, the other one is a guard that will not fire when the set changes.",
        )
    }

    /** Block comments and line comments removed; string literals left alone on purpose. */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
}
