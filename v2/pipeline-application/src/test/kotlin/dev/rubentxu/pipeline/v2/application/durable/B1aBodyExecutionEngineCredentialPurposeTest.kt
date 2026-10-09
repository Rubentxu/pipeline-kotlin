package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialBindingsPayload
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.credentials.CertificateBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.FileBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.SshUserPrivateKeyBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPasswordBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePasswordBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.ZipBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.boundPurpose
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * B1a — AUD-07: the `CredentialUsed.purpose` classification of body-leased credentials.
 *
 * ## The defect, and its closure
 *
 * `BodyExecutionEngine.invokeBodyChildren` published one `CredentialUsed` per binding, but its local
 * `when (binding.kind)` covered only 3 of the 7 binding kinds and ended in
 * `else -> BoundPurpose.API_KEY`. So a `file`, `certificate`, `zip` or `usernameColonPassword`
 * binding was reported to every external observer as an API key: a lying audit observation. The
 * duplicate was removed and both call sites now consume the single domain authority
 * `CredentialBindingSpec.boundPurpose`, which is total over the sealed family (the compiler refuses
 * an unmapped kind, so unknown kinds are unrepresentable). The raw-token boundary is fail-closed:
 * the compiled-payload codec rejects an unknown `kind` before it can become a spec.
 *
 * ## Fidelity (HARNESS FIDELITY LAW §1)
 *
 * **HF1 — in-process, through the production authority, named:** the body is dispatched through
 * `BodyExecutionEngine.invokeBodyChildren` — the same shared child loop the canonical coordinator
 * hands every block Step — with a `CanonicalBodyInvokerAdapter` and a real event sink. Only the
 * child dispatcher is a stub, and it is not the subject: the row measures the audit event the
 * engine emits, not the child.
 *
 * ## What the assertions are
 *
 * This is a **NON-REGRESSION** test (previously the defect was characterised in the AUD-07 triage
 * reading). Each row asserts a classification — the [BoundPurpose] carried by the event — never a
 * duration, size or ordering.
 */
@Timeout(60)
class B1aBodyExecutionEngineCredentialPurposeTest {

    /**
     * The single domain authority is total over the sealed family: all 7 kinds map to distinct,
     * correct [BoundPurpose] values.
     */
    @Test
    fun `all seven binding kinds map to their own BoundPurpose`() {
        val mapped = allBindingKinds().associate { it.kind to it.boundPurpose }
        assertEquals(
            mapOf(
                "string" to BoundPurpose.API_KEY,
                "usernamePassword" to BoundPurpose.USERNAME_PASSWORD,
                "sshUserPrivateKey" to BoundPurpose.SSH_KEY,
                "file" to BoundPurpose.FILE,
                "certificate" to BoundPurpose.CERTIFICATE,
                "zip" to BoundPurpose.ZIP,
                "usernameColonPassword" to BoundPurpose.USERNAME_COLON_PASSWORD,
            ),
            mapped,
            "AUD-07: the domain authority must cover all seven kinds; before the fix the body " +
                "engine reported file/certificate/zip/usernameColonPassword as API_KEY.",
        )
        assertEquals(7, BoundPurpose.entries.size, "the mapping must cover every BoundPurpose value")
    }

    /**
     * The engine's `CredentialUsed` events carry the SAME classification as the domain authority.
     * This is the row that measured the defect: pre-fix it saw `API_KEY` for the last four kinds.
     */
    @Test
    fun `the body engine emits the binding's own purpose for every kind`(@TempDir root: Path) {
        val sink = RecordingSink()
        val engine = BodyExecutionEngine(
            eventSink = sink,
            clock = object : dev.rubentxu.pipeline.v2.domain.durable.Clock {
                override fun now(): Instant = Instant.now()
            },
            bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
        )
        val bindings = allBindingKinds()

        val outcome = runBlocking {
            engine.invokeBodyChildren(
                block = blockWithOneChild(),
                runId = RunId("b1a-aud07"),
                stageName = "stage",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = ShOptions(
                    workspaceRoot = root,
                    captureStdout = false,
                    timeoutMs = null,
                    env = emptyMap(),
                ),
                parentBodyPath = emptyList(),
                executionContext = ExecutionContext.EMPTY,
                leaseBindings = bindings,
            ) { _, _, _, _, _, _, _, _ -> StepOutcome.Success }
        }

        assertEquals(StepOutcome.Success, outcome, "the stub body must complete")

        val used = sink.events.filterIsInstance<CredentialUsed>()
        assertEquals(
            bindings.size,
            used.size,
            "one CredentialUsed per binding must be emitted; observed=${used.map { it.purpose }}",
        )
        val byCredentialsId = used.associate { it.credentialsId.value to it.purpose }
        for (binding in bindings) {
            assertEquals(
                binding.boundPurpose,
                byCredentialsId[binding.credentialsId.value],
                "AUD-07 NON-REGRESSION: the CredentialUsed for '${binding.kind}' must carry " +
                    "purpose=${binding.boundPurpose}. Reporting API_KEY for file/certificate/zip/" +
                    "usernameColonPassword was the defect this row now pins.",
            )
        }
        println("B1a(AUD-07) CredentialUsed purposes = ${used.map { it.credentialsId.value to it.purpose }}")
    }

    /**
     * The raw-token boundary is fail-closed: an unknown `kind` never becomes a spec, so it can
     * never reach the audit mapping. The codec refuses it (the coordinator maps that refusal to a
     * typed schema `StepOutcome.Failure`, before any child runs).
     */
    @Test
    fun `the payload codec rejects an unknown kind token`() {
        val payload = """{"bindings":[{"kind":"martian","credentialsId":"x","variable":"V"}]}"""
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            CredentialBindingsPayload.decode(payload)
        }
        assertTrue(
            thrown.message.orEmpty().contains("martian"),
            "the rejection must name the unknown token; message='${thrown.message}'",
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun allBindingKinds(): List<CredentialBindingSpec> = listOf(
        StringBindingSpec(CredentialsId("c-string"), "V"),
        UsernamePasswordBindingSpec(CredentialsId("c-up"), "U", "P"),
        SshUserPrivateKeyBindingSpec(CredentialsId("c-ssh"), "K"),
        FileBindingSpec(CredentialsId("c-file"), "V"),
        CertificateBindingSpec(keystoreVariable = "K", credentialsId = CredentialsId("c-cert")),
        ZipBindingSpec(variable = "V", credentialsId = CredentialsId("c-zip")),
        UsernameColonPasswordBindingSpec(variable = "V", credentialsId = CredentialsId("c-colon")),
    )

    private fun blockWithOneChild(): BlockStepNode = BlockStepNode(
        id = StepId("withcredentials-block"),
        pluginStepId = PluginStepId("core.withCredentials"),
        payload = VersionedStepPayload("dsl-v1", "{}"),
        body = listOf<StepNode>(
            OpaqueStepNode(
                id = StepId("child"),
                pluginStepId = PluginStepId("core.echo"),
                payload = VersionedStepPayload("dsl-v1", "{}"),
            ),
        ),
    )

    private class RecordingSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }
}
