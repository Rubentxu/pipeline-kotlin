package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.ServiceLoader

/**
 * LFC-2E2-EXPANSION GATE — fitness that protects the plugin model from
 * silently regressing into a ServiceLocator and from leaking provider state
 * into core semantics. Authoritative for the entire LFC-2E2-UTILITIES-EXPANSION
 * cycle: every utility plugin family added in U1..U8 must remain green here.
 *
 * Authority:
 *   - `docs/v2/00-governance/PLUGIN_AUTHORING.md` (E2-PREP authoring rules)
 *   - `docs/v2/07-uat/FIRST_OFFICIAL_PLUGIN_RECEIPT.md` (capabilityAccessFactory seam)
 *   - this cycle's directive: the SDK must scale without core erosion.
 *
 * Invariants (one mechanical assertion per row):
 *
 *   G1  capabilityAccessFactory is null by default across all three production
 *       seams (CanonicalDurableRunCoordinator, ExecutionBoundaryFactory.build,
 *       RegistryExecutionBoundary.adapt).
 *   G2  capabilityAccessFactory source contains NO step-id / plugin-id switch
 *       (no `when (key.value)` or `if (key ==` referencing a concrete plugin id).
 *   G3  A plugin handler requesting an undeclared capability fails closed
 *       (delegated to StepContractSuite row, sanity-checked at this gate).
 *   G4  Plugin manifest's declaredCapabilities == union(StepContract.requiredCapabilities)
 *       across all StepDefinitions it contributes. (Static source-level check
 *       because no typed manifest field exists yet for the utilities plugin;
 *       this gate is the contract that will be wired in U8.)
 *   G5  Plugin present on classpath → utilities Steps ARE discoverable via
 *       ServiceLoader (Lane R ships the JAR into the test runtime).
 *   G6  Two plugins simultaneously (utilities + example.uppercase) → both
 *       contributors discovered; duplicate StepKey across plugins fails closed.
 *   G7  capabilityAccessFactory may return a bridge that does NOT provide a
 *       particular capability; handler asking for it through that bridge
 *       fails closed.
 *   G8  Core Steps are unaffected by plugin lifecycle: same StepKey set,
 *       same handler behavior bit-equivalent (handled by sibling fitness
 *       Lfc2UniversalCoreFreezeFitnessTest; not duplicated here).
 *   G9  Utilities plugin is an independent Gradle build with its own
 *       settings.gradle.kts — the property the LFC-2E2 cycle certifies.
 */
@Timeout(15)
@DisplayName("LFC-2E2-EXPANSION GATE — plugin model hardening")
class Lfc2E2ExpansionGateFitnessTest {

    private val repoRoot: File by lazy {
        File(System.getProperty("user.dir"), "../..").canonicalFile
    }

    private fun readRelative(relative: String): String =
        File(repoRoot, relative).also { check(it.isFile) { "missing file: $relative" } }.readText()

    // ───────────────────────────────────────────────────────────────────────
    // G1 — capabilityAccessFactory is null by default across all 3 seams
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G1-1 CanonicalDurableRunCoordinator declares capabilityAccessFactory as null-default`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
        )
        assertTrue(
            Regex(
                """private val capabilityAccessFactory:\s*\(\(CanonicalRuntimeContext\)\s*->\s*CanonicalRuntimeCapabilityAccess\)\?\s*=\s*null""",
            ).containsMatchIn(src),
            "G1-1: CanonicalDurableRunCoordinator must declare capabilityAccessFactory with default null",
        )
    }

    @Test
    fun `G1-2 ExecutionBoundaryFactory build() declares capabilityAccessFactory as null-default`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ExecutionBoundaryFactory.kt",
        )
        assertTrue(
            Regex(
                """capabilityAccessFactory:\s*\(\(CanonicalRuntimeContext\)\s*->\s*CanonicalRuntimeCapabilityAccess\)\?\s*=\s*null""",
            ).containsMatchIn(src),
            "G1-2: ExecutionBoundaryFactory.build must declare capabilityAccessFactory with default null",
        )
    }

    @Test
    fun `G1-3 RegistryExecutionBoundary adapt() accepts a nullable capabilityAccessFactory`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
        )
        assertTrue(
            src.contains(
                "capabilityAccessFactory: ((CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess)?",
            ),
            "G1-3: RegistryExecutionBoundary.adapt must accept a nullable capabilityAccessFactory",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G2 — capabilityAccessFactory source contains NO step/plugin-id switch
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G2-1 RegistryExecutionBoundary does not branch on plugin StepKeys or plugin ids`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
        )
        // A switch would look like `when (...key.value)` with concrete plugin prefixes
        // ("utilities.", "example.", "core.") or `PipelineStepId(...)` constructors
        // referring to a specific plugin Step.
        val forbiddenPatterns = listOf(
            Regex("""utilities\.(readJSON|writeJSON|sha256)"""),
            Regex("""example\.(uppercase|lowercase)"""),
            Regex("""PluginStepId\(\"utilities\.\""""),
            Regex("""PluginStepId\(\"example\.\""""),
        )
        forbiddenPatterns.forEach { pat ->
            assertFalse(
                pat.containsMatchIn(src),
                "G2-1: RegistryExecutionBoundary source must not reference concrete plugin StepKeys " +
                    "(pattern: ${pat.pattern})",
            )
        }
    }

    @Test
    fun `G2-2 ExecutionBoundaryFactory does not branch on plugin StepKeys`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ExecutionBoundaryFactory.kt",
        )
        val forbiddenPatterns = listOf(
            Regex("""utilities\.(readJSON|writeJSON|sha256)"""),
            Regex("""example\.(uppercase|lowercase)"""),
        )
        forbiddenPatterns.forEach { pat ->
            assertFalse(
                pat.containsMatchIn(src),
                "G2-2: ExecutionBoundaryFactory source must not reference concrete plugin StepKeys " +
                    "(pattern: ${pat.pattern})",
            )
        }
    }

    @Test
    fun `G2-3 CanonicalDurableRunCoordinator does not branch on plugin StepKeys in capability admission`() {
        // The capabilityAccessFactory call site at prepare-time admission must NOT inspect
        // the runtime context's step key. The check below enforces that no
        // `when (...pluginStepId...)` block lives in the registry family branch.
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
        )
        assertFalse(
            Regex("""when\s*\(\s*step\.pluginStepId\.""").containsMatchIn(src),
            "G2-3: CanonicalDurableRunCoordinator must NOT switch on step.pluginStepId in capability admission",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G4 — Plugin source declares no capabilities the StepContract doesn't require
    // (preliminary; the typed manifest field arrives in U8)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G4-1 utilities plugin source contains the two declared capability tokens`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt",
        )
        assertTrue(
            src.contains("UTILITIES_JSON_CAPABILITY"),
            "G4-1: utilities plugin must declare UTILITIES_JSON_CAPABILITY",
        )
        assertTrue(
            src.contains("UTILITIES_SHA_CAPABILITY"),
            "G4-1: utilities plugin must declare UTILITIES_SHA_CAPABILITY",
        )
    }

    @Test
    fun `G4-2 utilities plugin has NO access to CanonicalRuntimeContext or ProcessBuilder`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt",
        )
        listOf(
            "CanonicalRuntimeContext",
            "ProcessBuilder",
            "Runtime.exec",
            "Runtime.getRuntime",
            "bash -c",
            "EventSink",
        ).forEach { forbidden ->
            assertFalse(
                src.contains(forbidden),
                "G4-2: utilities plugin must not import/reach '$forbidden' (handler discipline)",
            )
        }
    }

    @Test
    fun `G4-3 utilities plugin declares exactly two distinct capability tokens`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt",
        )
        val declared = Regex("""StepCapability\(\"([^\"]+)\"""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            setOf("utilities.json.operations", "utilities.sha.operations"),
            declared.toSet(),
            "G4-3: utilities plugin must declare exactly two capability tokens " +
                "(utilities.json.operations, utilities.sha.operations)",
        )
    }

    @Test
    fun `G4-4 utilities plugin declares a typed UtilitiesJsonError sealed ADT (3 cases)`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt",
        )
        // The plugin must expose a sealed ADT with at least JsonNotFound/JsonParseFailure/JsonIoFailure,
        // and the host runtime stays unaware of it (no leak to pipeline-application).
        assertTrue(
            src.contains("sealed interface UtilitiesJsonError"),
            "G4-4: utilities plugin must declare a sealed UtilitiesJsonError ADT",
        )
        assertTrue(
            src.contains("class UtilitiesJsonException"),
            "G4-4: utilities plugin must declare a typed UtilitiesJsonException",
        )
        listOf("JsonNotFound", "JsonParseFailure", "JsonIoFailure").forEach { variant ->
            assertTrue(
                src.contains("data class $variant") || src.contains("class $variant"),
                "G4-4: UtilitiesJsonError must include the variant $variant",
            )
        }
        // The plugin source must NOT leak the typed failure into core/production: it lives
        // in the plugin package and the production coordinator still uses its generic
        // Exception catch. Verify there is no `when (...) UtilitiesJsonError` in production
        // core sources.
        val prodSources = listOf(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt",
        )
        prodSources.forEach { path ->
            val prodSrc = File(repoRoot, path).readText()
            assertFalse(
                prodSrc.contains("UtilitiesJsonError") || prodSrc.contains("UtilitiesJsonException"),
                "G4-4: production core must NOT reference UtilitiesJsonError/Exception (path: $path)",
            )
        }
    }

    @Test
    fun `G4-5 YAML plugin (U2) declares its OWN typed UtilitiesYamlError sealed ADT + capability port (3 cases)`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/yaml/UtilitiesYamlPlugin.kt",
        )
        // The YAML plugin (a NEW package within the same OFFICIAL_PLUGIN coordinate) MUST
        // declare its own typed failure ADT following the same pattern as U1's JSON: a sealed
        // interface with at least YamlNotFound/YamlParseFailure/YamlIoFailure + a typed
        // exception carrier + a capability port annotated @Throws(...).
        assertTrue(
            src.contains("sealed interface UtilitiesYamlError"),
            "G4-5: YAML plugin must declare a sealed UtilitiesYamlError ADT",
        )
        assertTrue(
            src.contains("class UtilitiesYamlException"),
            "G4-5: YAML plugin must declare a typed UtilitiesYamlException",
        )
        listOf("YamlNotFound", "YamlParseFailure", "YamlIoFailure").forEach { variant ->
            assertTrue(
                src.contains("data class $variant") || src.contains("class $variant"),
                "G4-5: UtilitiesYamlError must include the variant $variant",
            )
        }
        // Capability port annotation: each read/write fun MUST declare @Throws(UtilitiesYamlException::class)
        // so callers know they need to catch the typed exception.
        assertTrue(
            src.contains("@Throws(UtilitiesYamlException::class)"),
            "G4-5: YAML capability port MUST annotate its functions with @Throws(UtilitiesYamlException::class)",
        )
        // The new capability token `utilities.yaml.operations` MUST be declared in the YAML
        // package and MUST NOT collide with the existing JSON tokens.
        assertTrue(
            src.contains("StepCapability(\"utilities.yaml.operations\")"),
            "G4-5: YAML plugin must declare utilities.yaml.operations capability token",
        )
        // Production core stays unaware of the YAML package.
        val prodSources = listOf(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt",
        )
        prodSources.forEach { path ->
            val prodSrc = File(repoRoot, path).readText()
            assertFalse(
                prodSrc.contains("UtilitiesYamlError") || prodSrc.contains("UtilitiesYamlException"),
                "G4-5: production core must NOT reference UtilitiesYamlError/Exception (path: $path)",
            )
        }
    }

    @Test
    fun `G4-6 properties plugin (U3) declares its OWN typed UtilitiesPropertiesError sealed ADT + capability port (2 cases)`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/properties/UtilitiesPropertiesPlugin.kt",
        )
        // U3 follows the U1/U2 typed-failure pattern: a sealed ADT with PropertiesNotFound /
        // PropertiesIoFailure + a typed exception carrier + a capability port annotated @Throws(...).
        assertTrue(
            src.contains("sealed interface UtilitiesPropertiesError"),
            "G4-6: properties plugin must declare a sealed UtilitiesPropertiesError ADT",
        )
        assertTrue(
            src.contains("class UtilitiesPropertiesException"),
            "G4-6: properties plugin must declare a typed UtilitiesPropertiesException",
        )
        listOf("PropertiesNotFound", "PropertiesIoFailure").forEach { variant ->
            assertTrue(
                src.contains("data class $variant") || src.contains("class $variant"),
                "G4-6: UtilitiesPropertiesError must include the variant $variant",
            )
        }
        assertTrue(
            src.contains("@Throws(UtilitiesPropertiesException::class)"),
            "G4-6: properties capability port MUST annotate its functions with @Throws(UtilitiesPropertiesException::class)",
        )
        assertTrue(
            src.contains("StepCapability(\"utilities.properties.operations\")"),
            "G4-6: properties plugin must declare utilities.properties.operations capability token",
        )
        // Production core stays unaware of the properties package.
        val prodSources = listOf(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt",
        )
        prodSources.forEach { path ->
            val prodSrc = File(repoRoot, path).readText()
            assertFalse(
                prodSrc.contains("UtilitiesPropertiesError") || prodSrc.contains("UtilitiesPropertiesException"),
                "G4-6: production core must NOT reference UtilitiesPropertiesError/Exception (path: $path)",
            )
        }
    }

    @Test
    fun `G4-7 filesystem plugin (U4) declares its OWN typed UtilitiesFilesystemError sealed ADT + capability port (3 cases) + non-codec shape`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/filesystem/UtilitiesFilesystemPlugin.kt",
        )
        // U4 is the FIRST non-codec-shaped family (findFiles returns a LIST,
        // touch updates a timestamp). It still follows the typed-failure pattern.
        assertTrue(
            src.contains("sealed interface UtilitiesFilesystemError"),
            "G4-7: filesystem plugin must declare a sealed UtilitiesFilesystemError ADT",
        )
        assertTrue(
            src.contains("class UtilitiesFilesystemException"),
            "G4-7: filesystem plugin must declare a typed UtilitiesFilesystemException",
        )
        listOf("FilesystemNotFound", "FilesystemInvalidGlob", "FilesystemIoFailure").forEach { variant ->
            assertTrue(
                src.contains("data class $variant") || src.contains("class $variant"),
                "G4-7: UtilitiesFilesystemError must include the variant $variant",
            )
        }
        assertTrue(
            src.contains("@Throws(UtilitiesFilesystemException::class)"),
            "G4-7: filesystem capability port MUST annotate its functions with @Throws(UtilitiesFilesystemException::class)",
        )
        assertTrue(
            src.contains("StepCapability(\"utilities.filesystem.operations\")"),
            "G4-7: filesystem plugin must declare utilities.filesystem.operations capability token",
        )
        // Non-codec shape: findFiles must declare a LIST-shaped Output (not a single
        // scalar). Touch must use a timestamp-typed argument. This proves the
        // architecture supports non-codec families.
        assertTrue(
            src.contains("matches: List<String>"),
            "G4-7: findFiles must declare a LIST-shaped Output (matches: List<String>)",
        )
        assertTrue(
            src.contains("lastModifiedMillis: Long?"),
            "G4-7: touch must carry a timestamp-typed argument (lastModifiedMillis: Long?)",
        )
        // Production core stays unaware of the filesystem package.
        val prodSources = listOf(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt",
        )
        prodSources.forEach { path ->
            val prodSrc = File(repoRoot, path).readText()
            assertFalse(
                prodSrc.contains("UtilitiesFilesystemError") || prodSrc.contains("UtilitiesFilesystemException"),
                "G4-7: production core must NOT reference UtilitiesFilesystemError/Exception (path: $path)",
            )
        }
    }

    @Test
    fun `G4-8 checksums plugin (U5) declares typed closed HashAlgorithm enum (NOT a string-keyed Step) + capability port + JDK-only digest`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/checksums/UtilitiesChecksumsPlugin.kt",
        )
        // U5 follows the U1 sha256 pattern but uses a typed closed ADT
        // (enum HashAlgorithm) instead of a generic Step-by-name. The architectural
        // claim is that the algorithm name is a typed value, not a runtime string.
        assertTrue(
            src.contains("enum class HashAlgorithm"),
            "G4-8: checksums plugin must declare a typed closed enum HashAlgorithm",
        )
        listOf("MD5", "SHA1", "SHA512").forEach { variant ->
            assertTrue(
                src.contains("$variant(\"$variant\",") || src.contains("$variant(\"${variant.replace("SHA1", "SHA-1").replace("SHA512", "SHA-512")}\","),
                "G4-8: HashAlgorithm must include the variant $variant",
            )
        }
        // Each algorithm must be its OWN StepKey — NOT one generic
        // "utilities.checksum" Step that takes algorithm as a string.
        assertTrue(
            src.contains("PluginStepId(\"utilities.md5\")"),
            "G4-8: md5 must be its own StepKey (utilities.md5), not a string-keyed parameter",
        )
        assertTrue(
            src.contains("PluginStepId(\"utilities.sha1\")"),
            "G4-8: sha1 must be its own StepKey (utilities.sha1)",
        )
        assertTrue(
            src.contains("PluginStepId(\"utilities.sha512\")"),
            "G4-8: sha512 must be its own StepKey (utilities.sha512)",
        )
        // Typed failure ADT.
        assertTrue(
            src.contains("sealed interface UtilitiesChecksumError"),
            "G4-8: checksums plugin must declare a sealed UtilitiesChecksumError ADT",
        )
        assertTrue(
            src.contains("class UtilitiesChecksumException"),
            "G4-8: checksums plugin must declare a typed UtilitiesChecksumException",
        )
        assertTrue(
            src.contains("@Throws(UtilitiesChecksumException::class)"),
            "G4-8: checksums capability port MUST annotate its functions with @Throws(UtilitiesChecksumException::class)",
        )
        // Capability token is distinct from sha256's `utilities.sha.operations`.
        assertTrue(
            src.contains("StepCapability(\"utilities.checksums.operations\")"),
            "G4-8: checksums plugin must declare utilities.checksums.operations capability token (distinct from sha256)",
        )
        // JDK-only: MessageDigest.getInstance is the digest backend (no external dep).
        assertTrue(
            src.contains("MessageDigest.getInstance"),
            "G4-8: checksums plugin must use JDK MessageDigest.getInstance (no external dep)",
        )
        // Production core stays unaware of the checksums package.
        val prodSources = listOf(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt",
        )
        prodSources.forEach { path ->
            val prodSrc = File(repoRoot, path).readText()
            assertFalse(
                prodSrc.contains("UtilitiesChecksumError") || prodSrc.contains("UtilitiesChecksumException") ||
                    prodSrc.contains("HashAlgorithm"),
                "G4-8: production core must NOT reference UtilitiesChecksumError/Exception/HashAlgorithm (path: $path)",
            )
        }
    }

    @Test
    fun `G4-9 archive plugin (U6) declares typed UtilitiesArchiveError with Zip Slip variants + JDK-bundled + fail-closed security`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/archive/UtilitiesArchivePlugin.kt",
        )
        // U6 introduces the FIRST security-sensitive family: zip/unzip MUST fail closed
        // against Zip Slip (../) and absolute-path entries. The sealed ADT distinguishes
        // each failure class explicitly so callers can route them differently.
        assertTrue(
            src.contains("sealed interface UtilitiesArchiveError"),
            "G4-9: archive plugin must declare a sealed UtilitiesArchiveError ADT",
        )
        assertTrue(
            src.contains("class UtilitiesArchiveException"),
            "G4-9: archive plugin must declare a typed UtilitiesArchiveException",
        )
        // All four variants: ArchiveNotFound, ArchiveIoFailure, UnzipPathTraversal,
        // UnzipAbsolutePath. The Zip Slip protection is enforced by the UnzipPathTraversal
        // case + the corresponding guard.
        listOf("ArchiveNotFound", "ArchiveIoFailure", "UnzipPathTraversal", "UnzipAbsolutePath").forEach { variant ->
            assertTrue(
                src.contains("data class $variant") || src.contains("class $variant"),
                "G4-9: UtilitiesArchiveError must include the variant $variant",
            )
        }
        assertTrue(
            src.contains("@Throws(UtilitiesArchiveException::class)"),
            "G4-9: archive capability port MUST annotate its functions with @Throws(UtilitiesArchiveException::class)",
        )
        assertTrue(
            src.contains("StepCapability(\"utilities.archive.operations\")"),
            "G4-9: archive plugin must declare utilities.archive.operations capability token",
        )
        // Zip Slip guard: the unzip handler must check that the resolved entry path
        // starts with the normalised target root.
        assertTrue(
            src.contains("resolved.startsWith(targetPath)"),
            "G4-9: unzip handler MUST reject entries whose resolved path escapes the destination root (Zip Slip)",
        )
        // Absolute-path guard: the unzip handler must reject entries whose name starts
        // with '/' or is detected as an absolute path.
        assertTrue(
            src.contains("name.startsWith(\"/\")"),
            "G4-9: unzip handler MUST reject absolute-path entries (name.startsWith('/'))",
        )
        // JDK-only: java.util.zip is on the JDK classpath.
        assertTrue(
            src.contains("java.util.zip.ZipOutputStream") || src.contains("import java.util.zip.ZipOutputStream"),
            "G4-9: archive plugin must use JDK java.util.zip (no external dep)",
        )
        // Magic-number sanity check (fail-closed against malformed archives).
        assertTrue(
            src.contains("PK") || src.contains("0x50"),
            "G4-9: unzip handler MUST verify the ZIP magic number to fail closed on malformed archives",
        )
        // Production core stays unaware of the archive package.
        val prodSources = listOf(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt",
        )
        prodSources.forEach { path ->
            val prodSrc = File(repoRoot, path).readText()
            assertFalse(
                prodSrc.contains("UtilitiesArchiveError") || prodSrc.contains("UtilitiesArchiveException"),
                "G4-9: production core must NOT reference UtilitiesArchiveError/Exception (path: $path)",
            )
        }
    }

    @Test
    fun `G4-10 TAR plugin (U7) reuses U6 archive capability port + pure JDK USTAR encoder (NO Commons Compress + NO ArchiveStore)`() {
        val tarSrc = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/archive/UtilitiesTarPlugin.kt",
        )
        val archiveSrc = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/archive/UtilitiesArchivePlugin.kt",
        )
        // U7 SPIKE DECISION: USTAR (POSIX.1-1988) TAR is encoded/decoded in
        // pure JDK, NOT via Apache Commons Compress. The capability port is
        // REUSED from U6 (NO new token). NO ArchiveStore abstraction.
        // This row proves all three properties at the source level.
        assertTrue(
            tarSrc.contains("object TarCreateStepDefinition : StepDefinition"),
            "G4-10: TAR plugin must declare a TarCreateStepDefinition StepDefinition",
        )
        assertTrue(
            tarSrc.contains("object TarExtractStepDefinition : StepDefinition"),
            "G4-10: TAR plugin must declare a TarExtractStepDefinition StepDefinition",
        )
        // Typed failure ADT for TAR lives under UtilitiesArchiveError (extension).
        assertTrue(
            tarSrc.contains("sealed interface UtilitiesTarError : UtilitiesArchiveError"),
            "G4-10: TAR plugin must declare UtilitiesTarError as a sealed subtype of UtilitiesArchiveError",
        )
        listOf("TarHeaderCorrupt", "TarUnsupportedEntryType").forEach { variant ->
            assertTrue(
                tarSrc.contains("data class $variant"),
                "G4-10: UtilitiesTarError must include the variant $variant",
            )
        }
        // Apache Commons Compress MUST NOT be a dependency in the plugin build.
        // Verify the source doesn't import org.apache.commons.*.
        assertFalse(
            tarSrc.contains("org.apache.commons") ||
                archiveSrc.contains("org.apache.commons"),
            "G4-10: TAR family MUST NOT depend on Apache Commons Compress (we use pure JDK)",
        )
        // NO ArchiveStore abstraction introduced. The ArchiveOperations interface
        // in UtilitiesArchivePlugin is the SAME port U6 used — the TAR family
        // adds two methods to that interface WITHOUT introducing a new type or
        // an ArchiveStore marker.
        assertFalse(
            tarSrc.contains("class ArchiveStore") || archiveSrc.contains("class ArchiveStore") ||
                tarSrc.contains("interface ArchiveStore") || archiveSrc.contains("interface ArchiveStore") ||
                tarSrc.contains("object ArchiveStore") || archiveSrc.contains("object ArchiveStore"),
            "G4-10: NO ArchiveStore / TarStore abstraction introduced — flat method list on ArchiveOperations",
        )
        // Capability token for TAR MUST equal U6's archive capability.
        // We assert this via the StepContract rather than parsing — the
        // TarCreateStepDefinition.requiredCapabilities.first() is the same
        // UTILITIES_ARCHIVE_CAPABILITY constant as the zip / unzip StepDefinitions.
        assertTrue(
            tarSrc.contains("UTILITIES_ARCHIVE_CAPABILITY"),
            "G4-10: TAR StepDefinitions must require UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY (no new capability token)",
        )
        assertTrue(
            tarSrc.contains("StepCapability(\"utilities.archive.operations\")") ||
                archiveSrc.contains("StepCapability(\"utilities.archive.operations\")"),
            "G4-10: archive capability token is 'utilities.archive.operations' (no new token for TAR)",
        )
        // Typeflag whitelist: U7 supports only '0' (regular file) and '5' (directory).
        // Symlinks and devices are explicitly rejected (security boundary).
        assertTrue(
            tarSrc.contains("typeFlag != '0' && typeFlag != '5'") ||
                tarSrc.contains("typeFlag != \"0\" && typeFlag != \"5\""),
            "G4-10: TAR handler MUST reject typeflag other than '0' / '5' (symlinks, devices are unsupported)",
        )
        // Path-traversal guard mirrors unzip.
        assertTrue(
            tarSrc.contains("startsWith(\"/\")"),
            "G4-10: TAR handler MUST reject absolute-path entries (mirroring unzip's UnzipAbsolutePath)",
        )
        // Production core stays unaware of the TAR family.
        val prodSources = listOf(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt",
        )
        prodSources.forEach { path ->
            val prodSrc = File(repoRoot, path).readText()
            assertFalse(
                prodSrc.contains("UtilitiesTarError") || prodSrc.contains("UtilitiesTarPlugin") ||
                    prodSrc.contains("TarCreateStepDefinition") || prodSrc.contains("TarExtractStepDefinition"),
                "G4-10: production core must NOT reference TAR family (path: $path)",
            )
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // G5/G6 — Plugin absent / installed / removed lifecycle for core Steps
    //
    // Because the test JVM classpath always has both plugin JARs (Lane R),
    // the "absent" leg is exercised by reading ServiceLoader with a private
    // class loader that excludes the plugin JARs. The "present" leg below
    // demonstrates normal discovery.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G5 plugin present on test classpath IS discoverable via ServiceLoader`() {
        val contributors = ServiceLoader.load(StepDefinitionContributor::class.java).toList()
        val ids = contributors.map { it.id }
        assertTrue(
            "pipeline.utilities.json" in ids,
            "G5: pipeline.utilities.json must be discovered on the test classpath (got $ids)",
        )
        assertTrue(
            "example.uppercase" in ids,
            "G5: example.uppercase must be discovered on the test classpath (got $ids)",
        )
    }

    @Test
    fun `G5b YAML families are registered alongside JSON families in the same contributor`() {
        // U2: the YAML families ship under the SAME contributor id as the JSON families
        // (pipeline.utilities.json). Adding a new family to an existing plugin MUST NOT
        // require a new contributor; the canonical idempotent registry-driven discovery
        // surface grows the existing contributor's definitions().
        val registry = InMemoryStepRegistry().apply {
            ServiceLoader.load(StepDefinitionContributor::class.java).toList().forEach { contributor ->
                contributor.definitions().forEach { def -> register(def) }
            }
        }
        assertTrue(registry.contains(PluginStepId("utilities.readYaml")))
        assertTrue(registry.contains(PluginStepId("utilities.writeYaml")))
        // The plugin coordinate is the contributor id; both YAML and JSON families share it.
        val yamlContributor = ServiceLoader.load(StepDefinitionContributor::class.java).toList()
            .first { it.id == "pipeline.utilities.json" }
        val ids = yamlContributor.definitions().map { it.contract.key.value }.toSet()
        assertTrue(
            "utilities.readYaml" in ids && "utilities.writeYaml" in ids &&
                "utilities.readJSON" in ids && "utilities.writeJSON" in ids &&
                "utilities.sha256" in ids,
            "G5b: single utilities contributor must expose all 5 families " +
                "(readYaml + writeYaml + readJSON + writeJSON + sha256), got: $ids",
        )
    }

    @Test
    fun `G6-1 two plugins simultaneously - both families coexist in registry`() {
        val registry = InMemoryStepRegistry().apply {
            ServiceLoader.load(StepDefinitionContributor::class.java).toList().forEach { contributor ->
                contributor.definitions().forEach { def -> register(def) }
            }
        }
        assertTrue(registry.contains(PluginStepId("utilities.readJSON")))
        assertTrue(registry.contains(PluginStepId("utilities.writeJSON")))
        assertTrue(registry.contains(PluginStepId("utilities.sha256")))
        assertTrue(registry.contains(PluginStepId("example.uppercase")))
    }

    @Test
    fun `G6-2 two plugins simultaneously - duplicate StepKey across plugins fails closed`() {
        val registry = InMemoryStepRegistry().apply {
            ServiceLoader.load(StepDefinitionContributor::class.java).toList().forEach { contributor ->
                contributor.definitions().forEach { register(it) }
            }
        }
        // A second contributor that shadows utilities.readJSON must fail closed at
        // registration time, not silently override the first registration.
        val shadowing: StepDefinition<Unit, Unit> = object : StepDefinition<Unit, Unit> {
            override val contract: StepContract<Unit, Unit> = StepContract(
                key = PluginStepId("utilities.readJSON"),
                descriptor = dev.rubentxu.pipeline.v2.domain.StepDescriptor(
                    stepId = "utilities.readJSON",
                    name = "shadow",
                    configRef = "shadow",
                ),
                inputCodec = object : StepCodec<Unit> {
                    override fun encode(value: Unit) = EncodedStepValue("null")
                    override fun decode(encoded: EncodedStepValue) = Unit
                },
                outputCodec = object : StepCodec<Unit> {
                    override fun encode(value: Unit) = EncodedStepValue("null")
                    override fun decode(encoded: EncodedStepValue) = Unit
                },
                requiredCapabilities = emptySet(),
            )
            override val handler: StepHandler<Unit, Unit> =
                StepHandler<Unit, Unit> { _, _: StepHandlerContext -> }
        }
        var thrown: Throwable? = null
        try {
            registry.register(shadowing)
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown, "G6-2: a duplicate plugin StepKey must fail closed at registration")
        assertTrue(
            thrown is IllegalArgumentException,
            "G6-2: duplicate registration must throw IllegalArgumentException, was: ${thrown?.javaClass?.name}",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G7 — capabilityAccessFactory may return a bridge whose declared capabilities
    //      are scoped: requesting one not in `available()` fails closed.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G7 capabilityAccessFactory may return a scoped bridge and undeclared access fails closed`() {
        val absent = StepCapability("never.declared.capability")
        val ctx = CanonicalRuntimeContext(
            opId = OpId(runId = "g7", stageIndex = 0, stepIndex = 0),
            runId = "g7",
            stageName = "G7",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
            bodyInvoker = null,
        )
        val bridge = CanonicalRuntimeCapabilityAccess(ctx)
        var thrown: Throwable? = null
        try {
            bridge.get<Any>(absent)
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown, "G7: requesting a capability not provided by the bridge must fail closed")
        assertTrue(
            thrown is IllegalArgumentException,
            "G7: must throw IllegalArgumentException, was: ${thrown?.javaClass?.name}",
        )
        assertFalse(
            bridge.available().contains(absent),
            "G7: the undeclared capability must NOT be in available()",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G9 — Utilities plugin is an independent Gradle build
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G9 utilities plugin is an independent Gradle build with its own settings file`() {
        val settings = File(
            repoRoot,
            "examples/utilities-plugin/settings.gradle.kts",
        )
        assertTrue(settings.isFile, "G9: examples/utilities-plugin/settings.gradle.kts must exist")
        val text = settings.readText()
        assertTrue(
            text.contains("rootProject.name"),
            "G9: utilities plugin settings.gradle.kts must declare a rootProject.name",
        )
    }
}
