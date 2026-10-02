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
 * FIT-8  ShOptions                    carries FACTS, never SERVICES
 * FIT-9  the transport                never parks a thread on HTTP
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
 * FIT-4 deserves a note, and it is the one FIT that needed an amendment.
 * `pipeline-application` DOES depend on the http plugin module — it is the
 * composition root. What it must never do is import the plugin's
 * *vocabulary*: its codecs, its ADTs, its StepKey, its DSL façade.
 *
 * A capability, though, is an INSTANCE of a type the plugin defines, so
 * something outside the plugin has to construct it. The guard therefore names
 * exactly ONE exempt file — `CompositionRoot.kt` — rather than relaxing the
 * import ban for the whole module. Naming one file keeps the exemption from
 * spreading; an unnamed relaxation would have made this FIT aspirational, which
 * is the failure mode the whole rewrite exists to avoid.
 *
 * The root still decides whether the Step may run, and it does that through the
 * GENERIC `network.egress` verdict without referencing HTTP at all.
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
        ).filter { finding -> finding.file.toString() !in WIRING_EXEMPT_FILES }
        assertTrue(
            offenders.isEmpty(),
            "pipeline-application imports the HTTP plugin's own vocabulary in " +
                offenders.joinToString { "${it.file}:${it.line} (${it.token})" } + ". Only the " +
                "composition root may name a plugin's transport to WIRE it, and even there only " +
                "the transport. Naming codecs, ADTs, the StepKey or the façade in core is how a " +
                "plugin stops being a plugin.",
        )
    }

    /**
     * The single, explicitly named place where core may touch the plugin.
     *
     * A plugin's capability is an instance of a type the plugin defines, so
     * SOMETHING outside the plugin has to construct it; the composition root is
     * that somewhere. Amending the boundary to allow one named file — rather
     * than widening the import ban for the whole module — is what keeps the
     * exemption from spreading into vocabulary the root has no business knowing.
     *
     * Granted at H2 when the transport was wired. The root still decides
     * whether the Step may run at all, and it does that through the GENERIC
     * `network.egress` verdict without referencing HTTP.
     */
    private val WIRING_EXEMPT_FILES = listOf(
        v2.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CompositionRoot.kt",
        ).toString(),
    )

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

    // ── FIT-8: ShOptions is a carrier of facts, not a service registry ─────

    @Test
    fun `FIT-8 ShOptions does not carry live capabilities`() {
        // This law exists because it was broken. A `Map<StepCapability, Any>` was
        // briefly parked on `ShOptions` so the plugin seams would not have to
        // reach `CanonicalDurableRunCoordinator` — which kept the coordinator at
        // 552 lines and quietly converted a carrier of execution FACTS
        // (workspace, cwd, timeout, sandbox, egress policy) into a runtime
        // service locator. The next plugin's transport would have found the same
        // hole, and it would have happened by accretion rather than by decision.
        //
        // A ratchet satisfied by moving the coupling elsewhere is Goodharting.
        // The seam belongs in the coordinator and the ceiling moves honestly.
        val shOptions = v2.resolve(
            "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/ShOptions.kt",
        )
        val code = codeOnly(shOptions)
        for (forbidden in listOf(
            "Map<StepCapability",
            "Map<dev.rubentxu.pipeline.v2.domain.step.StepCapability",
            "pluginCapabilities",
        )) {
            assertTrue(
                !code.contains(forbidden),
                "ShOptions declares '$forbidden'. ShOptions carries FACTS and POLICIES for one " +
                    "execution; live ports and clients travel through RuntimeCapabilityContributor " +
                    "instead. A service registry on a config object is a coupling with no owner.",
            )
        }
        // The positive half: the POLICY belongs here, because it is a fact about
        // this execution and cannot be reconstructed downstream.
        assertTrue(
            code.contains("networkEgress"),
            "ShOptions must still carry the network egress policy: it is a per-execution fact " +
                "decided at the CLI boundary, and the runtime turns it into a capability.",
        )
    }

    // ── FIT-9: the transport is coroutine-native ───────────────────────────

    @Test
    fun `FIT-9 the transport awaits a future instead of parking a thread`() {
        // H3's structural half. The behavioural canaries (C1..C5) live with the
        // plugin; this one lives here because the shape it forbids is the one a
        // future refactor would most plausibly reintroduce, and a comment saying
        // "do not use withContext here" is not a constraint.
        val transport = v2.resolve(
            "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/JdkHttpTransport.kt",
        )
        val code = codeOnly(transport)
        for (forbidden in listOf(
            "withContext(Dispatchers.IO)",
            ".send(",
            ".get()",
        )) {
            assertTrue(
                !code.contains(forbidden),
                "JdkHttpTransport uses '$forbidden'. The transport must await a " +
                    "CompletableFuture: a blocking send parks an IO worker for the whole " +
                    "round-trip, which serialises concurrent requests and stops structured " +
                    "cancellation from reaching the request.",
            )
        }
        assertTrue(
            code.contains("sendAsync(") && code.contains(".await()"),
            "JdkHttpTransport must send asynchronously and await the future.",
        )
    }
}
