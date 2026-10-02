package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H1 — the OFFICIAL_PLUGIN contract of `http.request`.
 *
 * ## What this file is FOR
 *
 * It is the proof that the delivery classification is real and not a label.
 * Two claims are checked, and neither of them can be checked by looking at
 * this module alone — they need the real discovery mechanism and the real
 * compiler:
 *
 * 1. **The plugin is DISCOVERED.** `ServiceLoader` finds the contributor with
 *    no registration call anywhere in core. If a `.pipeline.kts` can write
 *    `httpRequest(...)`, it is because the JAR is on the classpath, not
 *    because the runtime was taught about the key.
 * 2. **The compiler is NOT MODIFIED.** The façade lowers a `httpRequest(...)`
 *    call all the way to canonical IR, and the result is a generic
 *    `RegistryStepSpec` / `OpaqueStepNode` carrying `http.request` — never a
 *    core `StepSpec` variant and never a compiler branch.
 *
 * Claim 2 is the load-bearing one. Claim 1 is plumbing; claim 2 is the thing
 * that distinguishes an open Step registry from a core that happens to have
 * a plugin-shaped file in it.
 */
class HttpPluginContractTest {

    // ── 1. discovery ─────────────────────────────────────────────────────

    @Test
    fun `ServiceLoader discovers the http contributor with no core registration`() {
        val discovered: List<StepDefinitionContributor> =
            java.util.ServiceLoader.load(StepDefinitionContributor::class.java).toList()

        assertTrue(
            discovered.any { it is HttpStepDefinitionContributor },
            "ServiceLoader did not find HttpStepDefinitionContributor. Found: " +
                discovered.map { it::class.simpleName },
        )
    }

    @Test
    fun `the discovered contributor registers the http request key as an OFFICIAL_PLUGIN`() {
        val contributor = HttpStepDefinitionContributor()
        val provider = contributor.registrations().first().provider

        assertEquals("http", contributor.id)
        assertEquals(
            Delivery.OFFICIAL_PLUGIN,
            provider.delivery,
            "http.request must be delivered as an OFFICIAL_PLUGIN. A core delivery here is " +
                "exactly the drift the ARCHITECTURAL_STOP recorded.",
        )
        assertTrue(
            provider.plugin.toString().contains("pipeline-plugin-http"),
            "Plugin namespace must be the policy's 'pipeline-plugin-http'; got ${provider.plugin}",
        )
        assertEquals(
            listOf(HttpRequestKey.VALUE),
            contributor.definitions().map { it.contract.key },
            "The contributor must expose exactly one Step family today.",
        )
    }

    @Test
    fun `the contract declares the transport and the egress permission`() {
        val contract = HttpRequestStep.definition.contract

        assertEquals(
            setOf(HTTP_TRANSPORT_CAPABILITY, NETWORK_EGRESS_CAPABILITY),
            contract.requiredCapabilities,
            "http.request needs its own transport AND the generic egress permission. Declaring " +
                "the permission is what makes a default run fail closed at admission instead of " +
                "reaching the network.",
        )
        assertTrue(
            contract.descriptor.effects.contains(Effect.NETWORKS),
            "The descriptor must declare Effect.NETWORKS: a remote POST is not a filesystem write.",
        )
        assertEquals(
            ReplayPolicy.NEVER,
            contract.descriptor.replayPolicy,
            "A request may have an effect the runtime cannot observe, so neither reuse nor " +
                "blind re-run is safe; the author retries with the `retry` block.",
        )
    }

    @Test
    fun `the step registers into an open registry like any external step`() {
        val registry = InMemoryStepRegistry()
        HttpRequestStep.registerInto(registry)

        assertTrue(
            registry.definition(HttpRequestKey.VALUE) != null,
            "http.request did not resolve from an open registry after registerInto",
        )
    }

    // ── 2. the compiler is not modified ─────────────────────────────────────

    @Test
    fun `the facade lowers to a generic registry node, never a core StepSpec variant`() {
        val spec = pipeline {
            stages {
                stage("Notify") {
                    httpRequest(
                        url = "https://example.test/hook",
                        method = HttpMethod.Post,
                        body = """{"ok":true}""",
                        contentType = "APPLICATION_JSON",
                    )
                }
            }
        }

        val http = spec.stages.single().steps.single()
        assertEquals(
            dev.rubentxu.pipeline.v2.dsl.StepSpec.RegistryStepSpec::class,
            http::class,
            "The façade must produce a generic registry step; it produced ${http::class.simpleName}. " +
                "A concrete StepSpec variant would mean core had learned a plugin key.",
        )
        assertEquals(
            PluginStepId("http.request"),
            (http as dev.rubentxu.pipeline.v2.dsl.StepSpec.RegistryStepSpec).stepKey,
        )
    }

    @Test
    fun `the lowered payload is byte-identical to what the plugin codec produces`() {
        val spec: PipelineSpec = pipeline {
            stages {
                stage("Notify") {
                    httpRequest(
                        url = "https://example.test/hook",
                        method = HttpMethod.Post,
                        customHeaders = listOf(HttpHeader.of("X-Token", "abc123")),
                        validResponseCodes = listOf(StatusRange.Single(201)),
                        timeoutSeconds = 15,
                    )
                }
            }
        }

        val lowered = spec.stages.single().steps.single() as dev.rubentxu.pipeline.v2.dsl.StepSpec.RegistryStepSpec
        assertEquals(HttpRequestKey.VALUE, lowered.stepKey, "the lowered step must carry the plugin key")
        assertEquals(
            HttpRequestCodec.encode(
                HttpRequestInput(
                    url = "https://example.test/hook",
                    method = HttpMethod.Post,
                    customHeaders = listOf(HttpHeader.of("X-Token", "abc123")),
                    validResponseCodes = listOf(StatusRange.Single(201)),
                    timeoutSeconds = 15,
                ),
            ).value,
            lowered.encodedInput.value,
        )
    }

    @Test
    fun `a second plugin could be added the same way, with no core change`() {
        // The point of the whole pivot, stated as an executable claim. The DSL
        // surface here comes from this module alone; the compiler never learned
        // `httpRequest`, so it equally never has to learn anything else.
        val spec = pipeline {
            stages {
                stage("Two") {
                    httpRequest("https://example.test/a")
                    httpRequest("https://example.test/b", method = HttpMethod.Head)
                }
            }
        }
        val steps = spec.stages.single().steps
        assertEquals(2, steps.size)
        assertTrue(steps.all { it.name == "registryStep" })
    }
}
