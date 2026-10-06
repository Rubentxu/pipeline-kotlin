package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

class FArch002ApplicationDependsInwardTest {

    /**
     * The third-party coordinates the application module is allowed to declare.
     *
     * This list previously held only three entries and was never enforced against
     * the real build file, because the old scanner could not read `libs.*` at all.
     * The entries below are what `pipeline-application/build.gradle.kts` actually
     * declares today, so from now on adding a fourth library is what turns this red.
     */
    private val allowedThirdParty = setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "org.jetbrains.kotlinx:kotlinx-serialization-json",
        "org.xerial:sqlite-jdbc",
        "org.junit.jupiter:junit-jupiter",
        "org.junit.platform:junit-platform-launcher",
    )

    private fun realCatalog(): VersionCatalog =
        VersionCatalog.load(ScannerSupport.v2Root().resolve("gradle/libs.versions.toml"))

    @Test
    fun `happy path — no violations at base`() {
        val root = ScannerSupport.v2Root()
        val buildFile = root.resolve("pipeline-application/build.gradle.kts")

        assertTrue(
            buildFile.toFile().exists(),
            "The application build file is the subject of this guard. A missing file used to " +
                "return early and report success, which is a silent no-op, not a pass.",
        )

        val lines = Files.readAllLines(buildFile)

        // Count implementation(project(":pipeline-domain")) lines
        val projectDepPattern = Pattern.compile("""implementation\s*\(\s*project\s*\(\s*"[^"]*:pipeline-domain"*\s*\)\s*\)""")
        val domainDepCount = lines.count { projectDepPattern.matcher(it).find() }
        assertEquals(1, domainDepCount, "Application must have exactly one implementation(project(\":pipeline-domain\"))")

        // Check no unallowed third-party deps
        val unallowedFindings = ScannerSupport.findDisallowedCoordinates(buildFile, realCatalog(), allowedThirdParty)
        assertTrue(unallowedFindings.isEmpty(), "Application must not have unallowed third-party deps: $unallowedFindings")
    }

    /**
     * The guard against the guard, for the same reason as FArch001's: an empty result
     * from a scanner that cannot read `libs.*` is indistinguishable from a clean
     * module, and this assertion is what tells the two apart.
     */
    @Test
    fun `the application build file is actually read, not silently skipped`() {
        val buildFile = ScannerSupport.v2Root().resolve("pipeline-application/build.gradle.kts")
        val declarations = ScannerSupport.dependencyDeclarations(buildFile, realCatalog())
        val thirdParty = declarations.filter { it.dependency is DeclaredDependency.CatalogDependency || it.dependency is DeclaredDependency.InlineDependency }

        assertTrue(
            thirdParty.isNotEmpty(),
            "FArch002 cannot guard the application's third-party surface if it reads nothing " +
                "out of it. The old scanner matched only quoted `implementation(...)`, so every " +
                "`libs.*` declaration went unnoticed.",
        )
        assertTrue(
            thirdParty.any { it.dependency is DeclaredDependency.CatalogDependency },
            "The application is expected to declare catalog-backed dependencies; an absence " +
                "means the scanner stopped recognising `libs.*`: $declarations",
        )
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects the synthetic violation`() {
            val fixture = tempDir.resolve("build.gradle.kts")
            fixture.toFile().writeText("""
                plugins { kotlin("jvm") }
                dependencies { implementation("io.ktor:ktor-client-core:2.3.0") }
            """.trimIndent())

            val findings = ScannerSupport.findDisallowedCoordinates(fixture, VersionCatalog.parse(""), allowedThirdParty)

            assertTrue(findings.isNotEmpty(), "Scanner must detect unallowed third-party dependency in fixture")
            val finding = findings.first()
            assertEquals("io.ktor:ktor-client-core", finding.token)
        }
    }

    @Nested
    inner class AllowlistFixture {
        @TempDir
        lateinit var depDir: Path

        private val catalog = VersionCatalog.parse(
            """
            [libraries]
            sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version = "3.46.1.3" }
            ktor-client-core = { module = "io.ktor:ktor-client-core", version = "2.3.0" }
            """.trimIndent()
        )

        private fun build(body: String): Path =
            depDir.resolve("build.gradle.kts").also { it.toFile().writeText("dependencies {\n$body\n}\n") }

        @Test
        fun `a catalog alias outside the allowlist is reported`() {
            val file = build("    implementation(libs.ktor.client.core)\n")

            val findings = ScannerSupport.findDisallowedCoordinates(file, catalog, allowedThirdParty)

            assertEquals(
                listOf("io.ktor:ktor-client-core"),
                findings.map { it.token },
                "The allowlist must be read through the catalog; the old scanner could not, " +
                    "so an unapproved library added via `libs.*` was invisible.",
            )
        }

        @Test
        fun `a catalog alias inside the allowlist is not reported`() {
            val file = build("    implementation(libs.sqlite.jdbc)\n")

            val findings = ScannerSupport.findDisallowedCoordinates(file, catalog, allowedThirdParty)

            assertTrue(
                findings.isEmpty(),
                "sqlite-jdbc is an approved dependency of the application; reporting it would " +
                    "train everyone to ignore this guard: $findings",
            )
        }

        @Test
        fun `local file dependencies are not third-party`() {
            val file = build(
                """    testImplementation(files(rootDir.resolve("../examples/example-uppercase-plugin/build/libs/plugin.jar")))"""
            )

            val findings = ScannerSupport.findDisallowedCoordinates(file, catalog, allowedThirdParty)

            assertTrue(
                findings.isEmpty(),
                "A path on this machine is not a third-party coordinate; reporting it would " +
                    "bury real findings under the locally-built plugin JARs: $findings",
            )
        }

        @Test
        fun `inner project dependencies are not third-party`() {
            val file = build(
                """
                    implementation(project(":pipeline-domain"))
                    implementation(project(":pipeline-step-sdk:http"))
                """.trimIndent().prependIndent("    ")
            )

            val findings = ScannerSupport.findDisallowedCoordinates(file, catalog, allowedThirdParty)

            assertTrue(findings.isEmpty(), "Module direction is FArchRP030's concern, not this one's: $findings")
        }
    }
}
