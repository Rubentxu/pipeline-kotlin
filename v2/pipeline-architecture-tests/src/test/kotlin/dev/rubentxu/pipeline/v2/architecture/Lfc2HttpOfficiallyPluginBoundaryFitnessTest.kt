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
 * FIT-10 the transport                never materialises a whole response body
 * FIT-11 the bounded subscriber       never asks for unbounded demand
 * FIT-12 the composition root         discovers capability contributors by default
 * FIT-13 the plugin                   ships the capability service manifest
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
        ).filter { finding -> finding.file.toString() !in wiringExemptFiles }
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
    private val wiringExemptFiles = listOf(
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

    // ── FIT-10: the body is bounded in memory, not bounded after the fact ──

    @Test
    fun `FIT-10 the transport never materialises a whole response body`() {
        // H4's structural half. The behavioural canaries (C1..C7) live with the
        // plugin; this one lives here because `BodyHandlers.ofByteArray()` is the
        // shortest, most idiomatic-looking line in the whole JDK HTTP API, and it is
        // exactly the line that makes a declared cap a fiction: it accumulates the
        // complete body and only then truncates to `maxBodyBytes`.
        val transport = v2.resolve(
            "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/JdkHttpTransport.kt",
        )
        val code = codeOnly(transport)
        for (forbidden in listOf(
            "ofByteArray()",
            "ofString()",
            "ofInputStream()",
            "ofFile(",
        )) {
            assertTrue(
                !code.contains(forbidden),
                "JdkHttpTransport uses BodyHandlers.$forbidden. The response body must be " +
                    "consumed by BoundedBodySubscriber: a built-in handler accumulates the " +
                    "whole body before producing a response, so maxBodyBytes would bound the " +
                    "returned value while the heap still held the entire download.",
            )
        }
        assertTrue(
            code.contains("fromSubscriber("),
            "JdkHttpTransport must route the body through the bounded subscriber.",
        )
    }

    @Test
    fun `FIT-11 the bounded subscriber asks for one batch and never for everything`() {
        // `request(Long.MAX_VALUE)` would also fit in memory — this subscriber only
        // keeps a bounded prefix — so it is not a memory bug. It is a different defect:
        // it is not backpressure. It asks the publisher for permission it never uses and
        // hands up the window the whole design exists to keep small.
        val subscriber = v2.resolve(
            "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/BoundedBodySubscriber.kt",
        )
        val code = codeOnly(subscriber)
        // Matched WITHOUT the trailing parenthesis on purpose. `Long.MAX_VALUE` reaches
        // this file in at least two shapes — `request(Long.MAX_VALUE)` and
        // `= Long.MAX_VALUE` — and a detector written for only the first one is a
        // detector that stops working the moment somebody writes the second.
        assertTrue(
            !code.contains("Long.MAX_VALUE"),
            "BoundedBodySubscriber must not request unbounded demand. Bounded retention is " +
                "not backpressure: request(Long.MAX_VALUE) is a promise the publisher is " +
                "never asked to keep, and it hands back the small window the whole design " +
                "exists to hold.",
        )
        assertTrue(
            code.contains("DEMAND_PER_BATCH"),
            "demand must be a named decision, not a literal buried in a callback",
        )
    }

    // ── FIT-12: the seam is presented by default, not only by a test ────────

    @Test
    fun `FIT-12 the composition root never defaults capability contributors to nothing`() {
        // The H4.5 defect, in one line. `capabilityContributors` defaulted to
        // `emptyList()` and the CLI never passed the argument, so `http.request` was
        // refused at admission in the installed distribution for a missing
        // `http.transport` — while every contract test passed, because those tests
        // hand-assembled the contributor they were asserting about.
        //
        // A default is invisible to behavioural testing from the outside: the only
        // honest way to pin it is to read it.
        //
        // S6/G MOVED THE DISCOVERY, NOT THE LAW. This row used to require the call to
        // `ExternalCapabilityContributorDiscovery.discover()` to sit in `CompositionRoot`.
        // It no longer does — capability discovery moved into `PluginComposition.resolve`,
        // inside the same classloader window that resolves the Steps these capabilities
        // back. Leaving a discovery call here would have re-created the fourth authority
        // that block removed: a plugin living below the TCCL would contribute its Step
        // and lose its capability.
        //
        // So what is pinned is the property, not the location: when the caller names no
        // contributors, the effective set is the one the composition already resolved —
        // never an empty list, and never a second discovery under whatever the TCCL
        // happens to be at that moment. `FArchPreResolvedCompositionAuthorityTest` pins
        // the other half structurally: discovery has exactly one production caller.
        val root = v2.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CompositionRoot.kt",
        )
        val code = codeOnly(root)

        assertTrue(
            !code.contains("RuntimeCapabilityContributor> = emptyList()"),
            "CompositionRoot must not default the contributor list to emptyList(); that is the " +
                "H4.5 defect verbatim.",
        )
        // A NON-NULL default of any kind is forbidden. `? = null` is allowed and is not a
        // default in the sense that matters: it carries no contributor list, so the effective
        // set still comes from the composition rather than from a value chosen at the call site.
        assertTrue(
            !code.contains("RuntimeCapabilityContributor> ="),
            "CompositionRoot must not default the contributor list to a NON-NULL value. S6/G " +
                "moved discovery into PluginComposition, so any concrete default here would be " +
                "a second authority — and the empty-list default is how http.request became " +
                "unrunnable in the first place.",
        )
        assertTrue(
            code.contains("capabilityContributors ?: composition.capabilityContributors"),
            "the effective contributors must fall back to the pre-resolved composition, not to a " +
                "fresh discovery. A caller that names none must still get every plugin's " +
                "capabilities.",
        )
    }

    @Test
    fun `FIT-13 the http plugin ships the capability contributor service file`() {
        // The other half of the same repair. Without this manifest entry, the capability
        // discovery that S6/G moved into `PluginComposition.resolve` resolves to an empty
        // list, which is indistinguishable from "no plugin needs a seam" at the only place
        // anyone looks.
        val serviceFile = v2.resolve(
            "pipeline-step-sdk/http/src/main/resources/META-INF/services/" +
                "dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor",
        )
        assertTrue(
            Files.exists(serviceFile),
            "the http plugin must declare its capability contributor at $serviceFile, or the " +
                "transport it owns is never presented to the runtime.",
        )
        val declared = Files.readString(serviceFile).trim()
        assertEquals(
            "dev.rubentxu.pipeline.v2.sdk.http.HttpCapabilityContributor",
            declared,
            "the service file must name the plugin's own contributor class",
        )
    }

    // ── FIT-14: the plugin asks the gate, and never becomes one ─────────────

    @Test
    fun `FIT-14 the http plugin may ask about egress but never decides it`() {
        // H6's structural half. The behavioural canaries (E1..E19) live with the
        // plugin; this one lives here because the shape it forbids is invisible from
        // the outside — every canary keeps passing when the plugin re-derives the
        // verdict instead of asking, exactly as M-http-25 demonstrated for
        // credentials.
        //
        // The failure this law prevents is not a bug in http.request. It is two
        // readers of the same policy reaching different verdicts, one of which is
        // "allowed", with no test failing on either run.
        val admission = v2.resolve(
            "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/EgressAdmission.kt",
        )
        val step = v2.resolve(
            "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/HttpRequestStep.kt",
        )
        val transport = v2.resolve(
            "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/HttpTransport.kt",
        )

        for ((label, file) in listOf(
            "EgressAdmission.kt" to admission,
            "HttpRequestStep.kt" to step,
            "HttpTransport.kt" to transport,
        )) {
            val code = codeOnly(file)
            for (forbidden in listOf(
                "RestrictedEgressGate",
                "EgressRule(",
                "AllowAll",
                "DenyAll",
            )) {
                assertTrue(
                    !code.contains(forbidden),
                    "$label names '$forbidden'. The plugin may ASK the runtime's egress gate " +
                        "and may interpret the answer it is given, but it must not name a gate " +
                        "implementation or construct a rule. The moment it does, the permission " +
                        "is being decided here, and 'the runtime owns it' is a comment.",
                )
            }
        }

        // The positive half, stated as the whole contract: one question, asked of
        // somebody else, before anything is opened.
        assertTrue(
            codeOnly(admission).contains("gate.decide("),
            "the plugin must actually ASK — reading a capability and ignoring it satisfies " +
                "every prohibition above while permitting everything.",
        )
        assertTrue(
            codeOnly(step).contains("egressAdmissionOf(intent.url, egressGate)"),
            "HttpRequestStep must ask about THIS request's destination before it sends, not " +
                "merely hold the capability.",
        )
    }

    @Test
    fun `FIT-15 the runtime bridge reads a gate property, not a list of known gates`() {
        // The companion law on the other side. `when (gate) { DenyAll -> ... }` at the
        // bridge would work today and would be the natural way to express "refuse unless
        // --allow-network" once the gate became an interface — and it would make adding
        // a restricted allowlist an edit to the runtime core. A property keeps the
        // runtime ignorant of which gates exist.
        val access = v2.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/" +
                "durable/CanonicalRuntimeCapabilityAccess.kt",
        )
        val code = codeOnly(access)

        assertTrue(
            code.contains("permitsAny"),
            "the bridge must ask the gate whether this run may reach the network at all.",
        )
        for (forbidden in listOf("RestrictedEgressGate", "AllowAll", "DenyAll")) {
            assertTrue(
                !code.contains(forbidden),
                "the runtime bridge names '$forbidden'. The runtime owns the permission, but it " +
                    "must not know which gates exist: a `when` over gate implementations is a " +
                    "core edit every future policy would need.",
            )
        }
    }

    // ── FIT-16: the retry a user can see is the only retry ──────────────────

    @Test
    fun `FIT-16 the request path cannot hide a retry`() {
        // WU-093 H7. `ReplayPolicy.NEVER` means http.request never re-sends itself on
        // replay — a promise worth exactly as much as the code's ability to keep it.
        // A transport that retried behind the engine's back would break that promise
        // invisibly: the journal would show one attempt while the server saw three,
        // and every POST that timed out after taking effect would be charged twice.
        //
        // The only sanctioned retry is the author's own `retry { httpRequest(...) }`,
        // which is visible, counted, and carries a distinct durable identity per
        // attempt. Anything the JDK does on its own is neither.
        //
        // Scoped to the code that runs ON a request. `HttpStepDefinitionContributor`
        // does touch System properties, but for `pipeline.http.*` provenance with a
        // save/restore, it is not on the request path, and rewriting plugin
        // registration is not this law's business.
        val onRequestPath = listOf(
            "JdkHttpTransport.kt",
            "HttpRequestStep.kt",
            "BoundedBodySubscriber.kt",
            "EgressAdmission.kt",
        )

        for (fileName in onRequestPath) {
            val code = codeOnly(
                v2.resolve(
                    "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/$fileName",
                ),
            )
            for (forbidden in listOf(
                "jdk.httpclient",
                "enableAllMethodRetry",
                "System.setProperty",
                "System.getProperty",
            )) {
                assertTrue(
                    !code.contains(forbidden),
                    "$fileName references '$forbidden'. A retry the engine cannot see is a " +
                        "resend nobody chose and nobody counted; a JVM-wide property set from " +
                        "the request path is worse still, because it is process-global and " +
                        "would outlive the call.",
                )
            }
        }

        // The JDK client is reached through sendAsync, never through the blocking
        // send(...) — one future per invocation, awaited once.
        val transport = codeOnly(
            v2.resolve(
                "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/JdkHttpTransport.kt",
            ),
        )
        assertTrue(
            !transport.contains(".send("),
            "JdkHttpTransport calls the blocking HttpClient.send(...). H3 replaced it with " +
                "sendAsync(); a silent return to the blocking call would put the whole body " +
                "read back on the calling thread.",
        )

        // The handler calls the port exactly once. A second call site — or a loop around
        // the first — is what an internal retry looks like in source, and it is the only
        // place the Step could grow one.
        val step = codeOnly(
            v2.resolve(
                "pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/HttpRequestStep.kt",
            ),
        )
        assertEquals(
            1,
            Regex("""transport\.send\s*\(""").findAll(step).count(),
            "HttpRequestStep must call transport.send(...) from exactly ONE place. A second " +
                "call site, or a loop around the first, is an internal retry: invisible in the " +
                "journal, uncounted by the author, and precisely what ReplayPolicy.NEVER " +
                "forbids.",
        )
    }

    // ── FIT-17: the flag is honoured, and the failure says why ──────────────

    @Test
    fun `FIT-17 the CLI honours --allow-network and reports why a run failed`() {
        // The wiring, not the class. `--allow-network` was parsed by the CLI, honoured by
        // the composition root, and threaded by NOBODY: `runCanonicalPipeline` declared the
        // parameter with a `false` default, so both call sites compiled, both runs stayed
        // fail-closed, and the operator's flag did nothing at all.
        //
        // A default on a 22-argument function is the quietest way to drop a permission.
        // It fails CLOSED, so every dashboard stayed green, and the only symptom was a
        // feature nobody could use. Pinned here rather than trusted to review.
        //
        // The second half is the reason the first half took H4.5, H7-D and H8-D1 to find:
        // a failed run printed "Pipeline finished with FAILURE" and nothing else, so a
        // Step refused at admission, refused at execute-time, and refused for a missing
        // network permission were byte-identical in the output. Silence is not cosmetic.
        val main = codeOnly(
            v2.resolve(
                "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt",
            ),
        )
        assertTrue(
            main.contains("allowNetwork = config.allowNetwork"),
            "Main must pass its parsed --allow-network into runCanonicalPipeline. Without it " +
                "the flag is accepted, recorded and discarded, and network.egress is " +
                "withheld on every run -- which is fail-closed and therefore looks safe while " +
                "being the exact opposite of what the operator asked for.",
        )
        // The CALL, not the declaration. A fitness that matches the definition still
        // passes after every call site is deleted, which is the one mutation that
        // matters here: M-cli-2 removed the call and left the function, and the
        // first version of this assertion stayed green through it.
        assertTrue(
            main.contains("reportRunFailure(runOutcome.failure)"),
            "Main must print WHY a run failed. A run that exits 1 without a reason emits " +
                "byte-identical output for a refused Step, a missing capability and a compile " +
                "error, which is exactly why three delivery defects shipped unnoticed.",
        )
    }
}
