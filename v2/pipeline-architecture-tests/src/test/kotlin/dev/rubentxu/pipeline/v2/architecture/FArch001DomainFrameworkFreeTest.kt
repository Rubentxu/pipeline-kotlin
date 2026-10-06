package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FArch001DomainFrameworkFreeTest {

    /**
     * Frameworks the domain must never reach for. Matched against whole segments of
     * a coordinate's group and artifact, so `io.kubernetes:client-java` is caught
     * and an unrelated artifact that merely shares letters is not.
     *
     * Each token is a whole segment, which is why the connection pool is spelled
     * `hikaricp` and not `hikari`: the Maven artifact is `com.zaxxer:HikariCP`, and
     * a segment is `hikaricp`. Spelling it `hikari` would have made this entry
     * silently inert.
     */
    private val forbiddenTokens = setOf("jenkins", "kubernetes", "koin", "docker", "flyway", "exposed", "jooq", "hikaricp")

    /** The repository's own version catalog. Aliases here are what `libs.*` resolves through. */
    private fun realCatalog(): VersionCatalog =
        VersionCatalog.load(ScannerSupport.v2Root().resolve("gradle/libs.versions.toml"))

    @Test
    fun `happy path — no violations at base`() {
        val root = ScannerSupport.v2Root()
        val domainSrc = root.resolve("pipeline-domain/src")
        val buildFile = root.resolve("pipeline-domain/build.gradle.kts")

        val importFindings = if (domainSrc.toFile().exists()) {
            ScannerSupport.findImports(domainSrc, forbiddenTokens)
        } else {
            emptyList()
        }

        val buildFindings = ScannerSupport.findForbiddenDependencies(buildFile, realCatalog(), forbiddenTokens)

        assertTrue(importFindings.isEmpty(), "Domain source must not import forbidden frameworks: $importFindings")
        assertTrue(buildFindings.isEmpty(), "Domain build must not depend on forbidden frameworks: $buildFindings")
    }

    /**
     * The guard against the guard.
     *
     * This assertion did not exist, and its absence is why the build-file half of
     * this test passed for five months without ever matching a line: the old
     * scanner only understood `implementation("literal")`, while the real build file
     * declares its dependencies through `libs.*` accessors and `test*` configurations.
     *
     * A scanner that finds nothing because nothing is wrong, and a scanner that finds
     * nothing because it cannot read, are indistinguishable from the outside — unless
     * you assert it read something. So this test pins the observable: the domain
     * build file declares dependencies, the scanner classifies them, and all of them
     * are allowed. If the parser regresses to blind, this goes red before the guard
     * above can quietly go vacuous again.
     */
    @Test
    fun `the domain build file is actually read, not silently skipped`() {
        val buildFile = ScannerSupport.v2Root().resolve("pipeline-domain/build.gradle.kts")
        val declarations = ScannerSupport.dependencyDeclarations(buildFile, realCatalog())

        assertTrue(
            declarations.isNotEmpty(),
            "FArch001 cannot guard the domain build file if it reads nothing out of it. " +
                "The scanner matched only quoted `implementation(...)`, so every `libs.*` and `test*` " +
                "declaration passed unnoticed.",
        )
        assertTrue(
            declarations.all { it.dependency is DeclaredDependency.CatalogDependency || it.dependency is DeclaredDependency.InlineDependency },
            "Every dependency in the domain build file must resolve to a real coordinate, " +
                "so no unresolved reading can hide behind an empty result: ${declarations.map { it.dependency }}",
        )
        assertTrue(
            declarations.any { it.configuration.startsWith("api") || it.dependency is DeclaredDependency.CatalogDependency },
            "The domain build file is expected to declare catalog-backed dependencies; " +
                "an absence means the scanner stopped recognising `libs.*`: $declarations",
        )
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects the synthetic violation`() {
            val fixture = tempDir.resolve("Forbidden.kt")
            fixture.toFile().writeText("package com.example\nimport io.kubernetes.client.openapi.apis.CoreV1Api\n")

            val findings = ScannerSupport.findImports(tempDir, forbiddenTokens)

            assertTrue(findings.isNotEmpty(), "Scanner must detect forbidden framework import in fixture")
            val finding = findings.first()
            assertEquals("kubernetes", finding.token)
            assertEquals("Forbidden.kt", finding.file.fileName.toString())
        }
    }

    /**
     * Each fixture below is bound to exactly one way the old scanner was blind, and
     * fails if that blindness returns.
     */
    @Nested
    inner class DependencyFixture {
        @TempDir
        lateinit var depDir: Path

        private val catalog = VersionCatalog.parse(
            """
            [libraries]
            sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version = "3.46.1.3" }
            hikaricp = { module = "com.zaxxer:HikariCP", version = "5.1.0" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version = "5.11.4" }
            """.trimIndent()
        )

        private fun build(body: String): Path =
            depDir.resolve("build.gradle.kts").also { it.toFile().writeText("dependencies {\n$body\n}\n") }

        @Test
        fun `catalog alias resolving to a forbidden coordinate is reported`() {
            val file = build("    implementation(libs.hikaricp)\n")

            val findings = ScannerSupport.findForbiddenDependencies(file, catalog, forbiddenTokens)

            assertEquals(
                listOf("hikaricp"),
                findings.map { it.token },
                "A `libs.*` accessor resolves through the catalog; the old scanner could not " +
                    "read it at all, so HikariCP in the domain was invisible.",
            )
            assertEquals(
                2,
                findings.first().line,
                "The finding must point at the declaring line, not at the enclosing " +
                    "`dependencies {` block or at the file.",
            )
        }

        @Test
        fun `forbidden coordinate in a test configuration is reported`() {
            val file = build("    testImplementation(\"io.kubernetes:client-java:1.0.0\")\n")

            val findings = ScannerSupport.findForbiddenDependencies(file, catalog, forbiddenTokens)

            assertEquals(
                listOf("kubernetes"),
                findings.map { it.token },
                "A test-scoped dependency is still a dependency of the module. The old " +
                    "pattern had no word boundary, so `testImplementation` never matched.",
            )
        }

        @Test
        fun `inner project dependencies are not reported`() {
            val file = build(
                """
                    implementation(project(":pipeline-events"))
                    api(project(":pipeline-step-sdk:api"))
                    implementation(libs.junit.jupiter)
                """.trimIndent().prependIndent("    ")
            )

            val findings = ScannerSupport.findForbiddenDependencies(file, catalog, forbiddenTokens)

            assertTrue(
                findings.isEmpty(),
                "Dependency direction between modules is FArchRP030's concern, not this " +
                    "scanner's; an `project(...)` must not be reported as a framework: $findings",
            )
        }

        @Test
        fun `an unresolvable alias is reported rather than skipped`() {
            val file = build("    implementation(libs.not.in.the.catalog)\n")

            val findings = ScannerSupport.findForbiddenDependencies(file, catalog, forbiddenTokens)

            assertEquals(
                listOf("unresolved:libs.not.in.the.catalog"),
                findings.map { it.token },
                "A coordinate the scanner cannot read is not a coordinate it cleared. " +
                    "Reporting it is what keeps this guard from becoming a rubber stamp.",
            )
        }

        @Test
        fun `a coordinate variable is reported as unresolved`() {
            val file = build(
                """
                    val coordinates = "io.kubernetes:client-java"
                    implementation(coordinates)
                """.trimIndent().prependIndent("    ")
            )

            val findings = ScannerSupport.findForbiddenDependencies(file, catalog, forbiddenTokens)

            assertEquals(
                listOf("unresolved:coordinates"),
                findings.map { it.token },
                "A dependency spelled through a variable must be surfaced for review, " +
                    "not read as safe because the scanner could not follow it.",
            )
        }

        @Test
        fun `clean domain dependencies produce no findings`() {
            val file = build(
                """
                    api(libs.sqlite.jdbc)
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                    testImplementation(libs.junit.jupiter)
                """.trimIndent().prependIndent("    ")
            )

            val findings = ScannerSupport.findForbiddenDependencies(file, catalog, forbiddenTokens)

            assertTrue(findings.isEmpty(), "A clean domain must stay clean, or nobody will trust the guard: $findings")
        }

        @Test
        fun `segment matching does not fire on shared letters`() {
            val file = build("    implementation(\"com.example:exposure-reporting:1.0\")\n")

            val findings = ScannerSupport.findForbiddenDependencies(file, catalog, forbiddenTokens)

            assertTrue(
                findings.isEmpty(),
                "`exposure-reporting` contains the letters of `exposed` but is not the framework; " +
                    "substring matching would raise a false alarm on every unrelated artifact.",
            )
        }

        @Test
        fun `commented dependencies are not reported`() {
            val file = build("    // implementation(libs.hikaricp)\n")

            val findings = ScannerSupport.findForbiddenDependencies(file, catalog, forbiddenTokens)

            assertTrue(findings.isEmpty(), "A commented dependency is not a dependency: $findings")
        }
    }
}
