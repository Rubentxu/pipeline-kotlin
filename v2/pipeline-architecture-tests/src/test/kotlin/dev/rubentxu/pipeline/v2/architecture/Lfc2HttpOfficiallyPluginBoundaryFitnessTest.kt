package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC-2E3 / WU-093 — the OFFICIAL_PLUGIN boundary of `http.request`.
 *
 * ## The law under test
 *
 * ```
 * http.request is a PLUGIN, so core must not know it exists.
 *
 * FIT-1  DslCompiledPipelineCompiler   does not contain "httpRequest"
 * FIT-2  StepSpec.sealedSubclasses     does not contain HttpRequest
 * FIT-3  CoreStepRegistryFactory       does not register an http key
 * FIT-4  pipeline-application         does not import the http plugin
 * FIT-5  the DSL façade               lowers exclusively via registryStep(...)
 * FIT-6  the wire bytes               come exclusively from HttpRequestCodec
 * FIT-7  ServiceLoader                discovers the contributor
 * ```
 *
 * ## Why these seven and not one
 *
 * The previous guard for this Step (`Lfc2HttpWireAuthorityFitnessTest`, removed
 * by this pivot) asserted ONE thing: that the compiler does not hand-write HTTP
 * wire JSON. That was the right guard for a `core.httpRequest` — a core Step
 * with a compiler branch — and it is a strictly WEAKER claim than the delivery
 * classification actually requires.
 *
 * A core-shaped implementation can satisfy a wire-JSON guard and still be core
 * closed: add `StepSpec.HttpRequest`, add the compiler branch, delegate the
 * payload to a codec, and every wire assertion passes while the ecosystem
 * policy is violated in full. The classification is a claim about **module
 * ownership and compiler ignorance**, so the guard has to assert those, and
 * seven checks are the minimum that covers them: absence in the compiler, in
 * the sealed IR, in the core registry, in the dependency graph, and in the DSL
 * lowering; plus presence of the plugin's own codec authority and of runtime
 * discovery.
 *
 * FIT-4 deserves a note. `pipeline-application` DOES depend on the http plugin
 * module — it is the composition root and must be able to wire the transport
 * and the egress capability. What it must never do is import the plugin's
 * *vocabulary*: its codecs, its ADTs, its StepKey. The guard therefore forbids
 * those imports specifically, and permits the module dependency, so the
 * assertion stays true instead of becoming an aspiration.
 *
 * The scan is comment-blind, like the rest of the Lfc2 source-scan family.
 */
class Lfc2HttpOfficiallyPluginBoundaryFitnessTest {

    private val v2 = ScannerSupport.v2Root()

    private val compiler = v2.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
    )
    private val stepSpec = v2.resolve(
        "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StepSpec.kt",
    )
    private val stageScopeBuilders = v2.resolve(
        "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StageScopeBuilders.kt",
    )
    private val registryFactory = v2.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt",
    )
    private val httpDsl = v2.resolve(
        "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/HttpDsl.kt",
    )
    private val servicesFile = v2.resolve(
        "pipeline-step-sdk/http/src/main/resources/META-INF/services/" +
            "dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor",
    )

    /** Comment-blind view of a source file. */
    private fun codeOnly(path: Path): String =
        read(path).lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    private fun read(path: Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    // ── FIT-1: the compiler has never heard of it ───────────────────────────

    @Test
    fun `FIT-1 the compiler does not contain the httpRequest key`() {
        val code = codeOnly(compiler)
        for (forbidden in listOf("httpRequest", "HttpRequestInput", "HttpRequestCodec", "core.http")) {
            assertTrue(
                !code.contains(forbidden),
                "DslCompiledPipelineCompiler names '$forbidden'. An OFFICIAL_PLUGIN must reach a " +
                    "pipeline through the generic registryStep path, never through a compiler " +
                    "branch (STEP_ECOSYSTEM_POLICY: pipeline-plugin-http).",
            )
        }
    }

    // ── FIT-2: the sealed IR is core vocabulary and stays closed ─────────────

    @Test
    fun `FIT-2 StepSpec has no HttpRequest variant`() {
        val code = codeOnly(stepSpec)
        assertTrue(
            !code.contains("HttpRequest"),
            "StepSpec gained an HttpRequest variant. The sealed IR is CORE: an external Step " +
                "lowers to StepSpec.RegistryStepSpec instead, which is why adding a Step no " +
                "longer extends a public sealed hierarchy (and why it no longer carries the " +
                "source-additive/exhaustive-when risk that G3.6 used to record).",
        )
    }

    // ── FIT-3: the core registry does not register it ───────────────────────

    @Test
    fun `FIT-3 the core step registry does not register an http key`() {
        val code = codeOnly(registryFactory)
        assertTrue(
            !code.contains("http") && !code.contains("HttpRequest"),
            "CoreStepRegistryFactory registers an HTTP Step. Bundled core Steps are core by " +
                "definition; the HTTP family is discovered through StepDefinitionContributor, " +
                "and adding it here would silently promote it to a privileged core path.",
        )
    }

    // ── FIT-4: application composes the plugin, never speaks its language ───

    @Test
    fun `FIT-4 pipeline-application does not import the http plugin vocabulary`() {
        val appRoot = v2.resolve("pipeline-application/src/main/kotlin")
        val offenders = ScannerSupport.findImports(
            appRoot,
            listOf("dev.rubentxu.pipeline.v2.sdk.http."),
        )
        assertTrue(
            offenders.isEmpty(),
            "pipeline-application imports the HTTP plugin's own vocabulary in " +
                offenders.joinToString { "${it.file}:${it.line} (${it.token})" } + ". The " +
                "composition root MAY depend on the module to wire the transport, but it must " +
                "not name the plugin's codecs, ADTs or StepKey — that is how a plugin stops " +
                "being a plugin.",
        )
    }

    // ── FIT-5: the façade lowers only through the generic primitive ─────────

    @Test
    fun `FIT-5 the http DSL facade lowers exclusively through registryStep`() {
        val code = codeOnly(httpDsl)
        assertTrue(
            code.contains("registryStep("),
            "The http façade must lower through the generic registryStep primitive; core " +
                "provides only that primitive and plugins provide only the typed façade.",
        )
        assertTrue(
            !code.contains("StepSpec."),
            "The http façade names a concrete StepSpec. Plugin DSL constructs data and lowers " +
                "generically; it never selects a core IR variant.",
        )
        // The typed builder must not have crept back into core.
        assertTrue(
            !codeOnly(stageScopeBuilders).contains("httpRequest"),
            "StageScopeBuilders still declares httpRequest. The builder belongs to the plugin: " +
                "that is what lets the Step be absent from a build that does not ship the JAR.",
        )
    }

    // ── FIT-6: one authority for the wire bytes ────────────────────────────

    @Test
    fun `FIT-6 the wire bytes come exclusively from the plugin codec`() {
        val code = codeOnly(httpDsl)
        assertTrue(
            code.contains("HttpRequestCodec.encode("),
            "The façade must encode through HttpRequestCodec, the single authority for this " +
                "wire format (M-http-5A: bypassing it must fail this guard).",
        )
        assertTrue(
            !code.contains("EncodedStepValue(\""),
            "The façade constructs a wire literal directly. That is the inline-wire debt the " +
                "single-authority rule exists to prevent.",
        )
    }

    // ── FIT-7: it is discovered, not hand-wired ─────────────────────────────

    @Test
    fun `FIT-7 ServiceLoader discovers the http contributor`() {
        assertTrue(Files.exists(servicesFile), "Missing ServiceLoader registration: $servicesFile")
        assertEquals(
            "dev.rubentxu.pipeline.v2.sdk.http.HttpStepDefinitionContributor",
            read(servicesFile).trim(),
            "The http contributor must be the single declared StepDefinitionContributor.",
        )
    }
}
