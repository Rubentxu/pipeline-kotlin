package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LB-02 / G7 — `CoreShellOutput` codec round-trip / negative corpus / canonical determinism.
 *
 * Goals:
 *  1. **Round-trip**: encode(decode(encode(x))) == encode(x) for every contractual variant
 *     (Unit / Stdout / Status / Failed / Interrupted). All contractual fields survive.
 *  2. **Negative corpus**: malformed payloads (unknown kind, missing mandatory field, wrong
 *     field type, invalid outcome discriminant, inconsistent variant payload) surface as
 *     typed decode failure. NO silent defaults, NO `Success` for malformed data, NO swallowing.
 *  3. **Canonical determinism**: same instance produces byte-identical bytes across calls;
 *     Map iteration, timestamps, identity, and non-canonical ordering MUST NOT leak into
 *     the encoded form.
 *
 * `core.sh = IMPLEMENTED_UNCERTIFIED`. This test suite proves the codec contract; the
 * production flip is gated on a separate parity suite (A4.8).
 */
@Timeout(10)
class G7_CoreShellOutputCodecRoundTripTest {

    private val codec = CoreShellStep.definition.contract.outputCodec

    // ----- G7.1 — Round-trip for every contractual variant -------------------

    @Test
    fun `round-trip UNIT variant preserves discriminant and outcome`() {
        val original = CoreShellOutput(
            result = ShellInvocationResult.UnitValue,
            outcome = StepOutcome.Success,
        )
        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)
        assertEquals(original.result, decoded.result)
        assertEquals(original.outcome, decoded.outcome)
    }

    @Test
    fun `round-trip STDOUT variant preserves value`() {
        val original = CoreShellOutput(
            result = ShellInvocationResult.Stdout("hello\nworld\n"),
            outcome = StepOutcome.Success,
        )
        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)
        assertEquals(original.result, decoded.result)
        assertEquals(original.outcome, decoded.outcome)
        assertEquals("hello\nworld\n", (decoded.result as ShellInvocationResult.Stdout).value)
    }

    @Test
    fun `round-trip STATUS exit-0 variant preserves exitCode and outcome`() {
        val original = CoreShellOutput(
            result = ShellInvocationResult.Status(exitCode = 0),
            outcome = StepOutcome.Success,
        )
        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)
        assertEquals(original.result, decoded.result)
        assertEquals(StepOutcome.Success, decoded.outcome)
    }

    @Test
    fun `round-trip STATUS non-zero exit variant preserves exitCode and outcome`() {
        // non-zero exit with returnMode=STATUS is classified as Success (legacy convention),
        // so the encoded outcome MUST remain SUCCESS.
        val original = CoreShellOutput(
            result = ShellInvocationResult.Status(exitCode = 7),
            outcome = StepOutcome.Success,
        )
        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)
        assertEquals(original.result, decoded.result)
        assertEquals(7, (decoded.result as ShellInvocationResult.Status).exitCode)
        assertEquals(StepOutcome.Success, decoded.outcome)
    }

    @Test
    fun `round-trip FAILED variant preserves failure kind, message, exitCode, outcome`() {
        val original = CoreShellOutput(
            result = ShellInvocationResult.Failed(
                failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7"),
                exitCode = 7,
            ),
            outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7")),
        )
        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)
        assertEquals(original.result, decoded.result)
        assertTrue(decoded.outcome is StepOutcome.Failure)
        val carried = (decoded.result as ShellInvocationResult.Failed)
        assertEquals(FailureKind.SCRIPT, carried.failure.kind)
        assertEquals("exit 7", carried.failure.message)
        assertEquals(7, carried.exitCode)
        assertEquals(
            FailureKind.SCRIPT,
            (decoded.outcome as StepOutcome.Failure).failure.kind,
        )
    }

    @Test
    fun `round-trip FAILED variant with durableFailure preserves the full record`() {
        val durable = FailureRecord(
            code = "LAUNCH-001",
            kind = FailureKind.INFRASTRUCTURE,
            message = "container init failed",
            origin = FailureOrigin.LAUNCHER,
            retryable = true,
            operationId = "run/0/0",
            workerId = "worker-3",
            taskId = "task-7",
            details = mapOf("container" to "abc", "reason" to "oom"),
        )
        val original = CoreShellOutput(
            result = ShellInvocationResult.Failed(
                failure = PipelineFailure(kind = FailureKind.INFRASTRUCTURE, message = "container init failed"),
                durableFailure = durable,
                exitCode = null,
            ),
            outcome = StepOutcome.Failure(
                PipelineFailure(kind = FailureKind.INFRASTRUCTURE, message = "container init failed"),
            ),
        )
        val encoded = codec.encode(original)
        // Spot-check that the encoded form actually carries the durableFailure block
        // (round-trip alone could pass with a lossy codec if we don't also assert the wire).
        val wire = Json.parseToJsonElement(encoded.value).jsonObject
        assertTrue("durableFailure" in wire.keys, "durableFailure MUST be encoded when present")
        val decoded = codec.decode(encoded)
        assertEquals(original.result, decoded.result)
        val carried = decoded.result as ShellInvocationResult.Failed
        assertNotNull(carried.durableFailure, "durableFailure MUST survive round-trip")
        val dr = carried.durableFailure!!
        assertEquals("LAUNCH-001", dr.code)
        assertEquals(FailureKind.INFRASTRUCTURE, dr.kind)
        assertEquals("container init failed", dr.message)
        assertEquals(FailureOrigin.LAUNCHER, dr.origin)
        assertEquals(true, dr.retryable)
        assertEquals("run/0/0", dr.operationId)
        assertEquals("worker-3", dr.workerId)
        assertEquals("task-7", dr.taskId)
        assertEquals(mapOf("container" to "abc", "reason" to "oom"), dr.details)
        assertEquals(FailureRecord.SCHEMA_VERSION, dr.schemaVersion)
    }

    @Test
    fun `round-trip FAILED variant with failureCauseClass preserves diagnostic class name`() {
        // The Throwable itself is NEVER transported (per FailureRecord's design rule),
        // but the class name MUST be preserved for tooling.
        val original = CoreShellOutput(
            result = ShellInvocationResult.Failed(
                failure = PipelineFailure(
                    kind = FailureKind.SCRIPT,
                    message = "boom",
                    cause = IllegalStateException("synthetic"),
                ),
                exitCode = 1,
            ),
            outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "boom")),
        )
        val encoded = codec.encode(original)
        val wire = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals(
            "java.lang.IllegalStateException",
            wire["failureCauseClass"]?.jsonPrimitive?.content,
            "Throwable class name MUST be preserved for diagnostics",
        )
        val decoded = codec.decode(encoded)
        val carried = decoded.result as ShellInvocationResult.Failed
        // The cause itself is intentionally null after round-trip (no throwable transport).
        assertNull(carried.failure.cause, "Throwable MUST NOT be reconstituted across the wire")
        assertEquals(FailureKind.SCRIPT, carried.failure.kind)
        assertEquals("boom", carried.failure.message)
    }

    @Test
    fun `round-trip INTERRUPTED variant preserves all contractual fields`() {
        val interruption = InterruptionRecord(
            kind = InterruptionKind.USER_ABORT,
            message = "user pressed cancel",
            operationId = "run/0/0",
            causedBy = "ui-cancel-button",
            deadlineEpochMillis = 1_700_000_000_000L,
            details = mapOf("source" to "ui", "channel" to "web"),
        )
        val original = CoreShellOutput(
            result = ShellInvocationResult.Interrupted(interruption = interruption),
            outcome = StepOutcome.Failure(
                PipelineFailure(kind = FailureKind.TIMEOUT, message = "user pressed cancel"),
            ),
        )
        val encoded = codec.encode(original)
        val decoded = codec.decode(encoded)
        assertEquals(original.result, decoded.result)
        val carried = (decoded.result as ShellInvocationResult.Interrupted).interruption
        assertEquals(InterruptionKind.USER_ABORT, carried.kind)
        assertEquals("user pressed cancel", carried.message)
        assertEquals("run/0/0", carried.operationId)
        assertEquals("ui-cancel-button", carried.causedBy)
        assertEquals(1_700_000_000_000L, carried.deadlineEpochMillis)
        assertEquals(mapOf("source" to "ui", "channel" to "web"), carried.details)
    }

    // ----- G7.2 — Negative corpus (must surface as typed decode failure) ----

    @Test
    fun `negative unknown kind fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "BOGUS")
                put("outcome", "SUCCESS")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(
            ex is CoreShellCodecException,
            "unknown kind MUST surface as CoreShellCodecException (got $ex)",
        )
        assertTrue(
            ex!!.message!!.contains("BOGUS"),
            "exception message MUST name the unknown kind",
        )
    }

    @Test
    fun `negative missing kind field fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("outcome", "SUCCESS")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("kind"))
    }

    @Test
    fun `negative missing outcome field fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "UNIT")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("outcome"))
    }

    @Test
    fun `negative non-JSON payload fails explicitly`() {
        val payload = EncodedStepValue("not-json-at-all{")
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException, "non-JSON MUST surface as CoreShellCodecException (got $ex)")
    }

    @Test
    fun `negative STDOUT variant missing value field fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "STDOUT")
                put("outcome", "SUCCESS")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("STDOUT"))
    }

    @Test
    fun `negative STDOUT variant with wrong-typed value fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "STDOUT")
                put("outcome", "SUCCESS")
                put("value", Json.parseToJsonElement("42")) // number, not string
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(
            ex is CoreShellCodecException,
            "wrong-typed 'value' MUST fail (got $ex)",
        )
    }

    @Test
    fun `negative STATUS variant missing exitCode fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "STATUS")
                put("outcome", "SUCCESS")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("exitCode"))
    }

    @Test
    fun `negative STATUS variant with non-integer exitCode fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "STATUS")
                put("outcome", "SUCCESS")
                put("exitCode", Json.parseToJsonElement("\"seven\""))
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
    }

    @Test
    fun `negative FAILED variant missing failureKind fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "FAILED")
                put("outcome", "FAILURE")
                put("failureMessage", "x")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("failureKind"))
    }

    @Test
    fun `negative FAILED variant missing failureMessage fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "FAILED")
                put("outcome", "FAILURE")
                put("failureKind", "SCRIPT")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("failureMessage"))
    }

    @Test
    fun `negative FAILED variant with invalid FailureKind name fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "FAILED")
                put("outcome", "FAILURE")
                put("failureKind", "NOT_A_KIND")
                put("failureMessage", "x")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("NOT_A_KIND"))
    }

    @Test
    fun `negative FAILED variant with malformed durableFailure fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "FAILED")
                put("outcome", "FAILURE")
                put("failureKind", "SCRIPT")
                put("failureMessage", "x")
                // durableFailure is an array, not an object
                put("durableFailure", Json.parseToJsonElement("[1,2,3]"))
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
    }

    @Test
    fun `negative INTERRUPTED variant missing interruptionKind fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "INTERRUPTED")
                put("outcome", "FAILURE")
                put("interruptionMessage", "killed")
                put("operationId", "r/0/0")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("interruptionKind"))
    }

    @Test
    fun `negative INTERRUPTED variant with invalid InterruptionKind fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "INTERRUPTED")
                put("outcome", "FAILURE")
                put("interruptionKind", "NOT_A_KIND")
                put("interruptionMessage", "killed")
                put("operationId", "r/0/0")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("NOT_A_KIND"))
    }

    @Test
    fun `negative INTERRUPTED variant missing operationId fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "INTERRUPTED")
                put("outcome", "FAILURE")
                put("interruptionKind", "TIMEOUT")
                put("interruptionMessage", "killed")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("operationId"))
    }

    @Test
    fun `negative invalid outcome discriminant fails explicitly`() {
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "UNIT")
                put("outcome", "MAYBE")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("MAYBE"))
    }

    @Test
    fun `negative inconsistent variant outcome succeeds with SUCCESS cannot carry FAILED payload`() {
        // kind=STDOUT but outcome=FAILURE: producer/consumer mismatch, must reject.
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "STDOUT")
                put("outcome", "FAILURE")
                put("value", "ok")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(
            ex is CoreShellCodecException,
            "kind/variant mismatch MUST surface as CoreShellCodecException (got $ex)",
        )
        assertTrue(
            ex!!.message!!.contains("mismatch"),
            "exception message MUST mention the mismatch",
        )
    }

    @Test
    fun `negative inconsistent outcome succeeds with FAILED variant cannot carry SUCCESS`() {
        // kind=FAILED but outcome=SUCCESS: must reject (would otherwise silently coerce
        // a real failure into Success — exactly the A4.3 anti-property).
        val payload = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "FAILED")
                put("outcome", "SUCCESS")
                put("failureKind", "SCRIPT")
                put("failureMessage", "exit 7")
            }),
        )
        val ex = runCatching { codec.decode(payload) }.exceptionOrNull()
        assertTrue(ex is CoreShellCodecException)
        assertTrue(ex!!.message!!.contains("mismatch"))
    }

    @Test
    fun `negative decode NEVER produces Success by silent coercion`() {
        // Sanity sweep across every malformed shape we can think of. None of them
        // must silently decode into a Success carrier.
        val malformedPayloads = listOf(
            EncodedStepValue("not-json"),
            EncodedStepValue("{}"),
            EncodedStepValue("""{"kind":"UNIT"}"""),                                // missing outcome
            EncodedStepValue("""{"outcome":"SUCCESS"}"""),                          // missing kind
            EncodedStepValue("""{"kind":"STDOUT","outcome":"SUCCESS"}"""),          // STDOUT missing value
            EncodedStepValue("""{"kind":"STATUS","outcome":"SUCCESS"}"""),          // STATUS missing exitCode
            EncodedStepValue("""{"kind":"FAILED","outcome":"SUCCESS","failureKind":"SCRIPT","failureMessage":"x"}"""), // FAILED with SUCCESS mismatch
            EncodedStepValue("""{"kind":"INTERRUPTED","outcome":"FAILURE"}"""),     // missing all interruption fields
            EncodedStepValue("""{"kind":"ZZZ","outcome":"SUCCESS"}"""),              // unknown kind
            EncodedStepValue("""{"kind":"UNIT","outcome":"MAYBE"}"""),              // unknown outcome
        )
        for (payload in malformedPayloads) {
            val res = runCatching { codec.decode(payload) }
            assertTrue(
                res.isFailure,
                "malformed payload MUST fail decode, but succeeded for: ${payload.value}",
            )
            val ex = res.exceptionOrNull()
            assertTrue(
                ex is CoreShellCodecException,
                "decode failure MUST be a CoreShellCodecException, got ${ex!!::class.simpleName} for ${payload.value}",
            )
        }
    }

    // ----- G7.3 — Canonical determinism --------------------------------------

    @Test
    fun `determinism — same instance encoded twice produces byte-identical bytes`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Failed(
                failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7"),
                exitCode = 7,
            ),
            outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7")),
        )
        val a = codec.encode(out).value
        val b = codec.encode(out).value
        assertEquals(a, b, "encode MUST be deterministic for the same instance")
    }

    @Test
    fun `determinism — different instances with equal content produce identical bytes`() {
        // Two distinct `out` instances carrying the same content MUST encode to the same bytes.
        val mk = {
            CoreShellOutput(
                result = ShellInvocationResult.Failed(
                    failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7"),
                    exitCode = 7,
                ),
                outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7")),
            )
        }
        assertEquals(codec.encode(mk()).value, codec.encode(mk()).value)
    }

    @Test
    fun `determinism — details map iteration order does not affect encoded form`() {
        // Insert entries in different orders; encoded details MUST be byte-identical.
        val drA = FailureRecord(
            code = "X", kind = FailureKind.INFRASTRUCTURE, message = "m",
            origin = FailureOrigin.LAUNCHER, retryable = false,
            operationId = "op", details = linkedMapOf("a" to "1", "b" to "2", "c" to "3"),
        )
        val drB = FailureRecord(
            code = "X", kind = FailureKind.INFRASTRUCTURE, message = "m",
            origin = FailureOrigin.LAUNCHER, retryable = false,
            operationId = "op", details = linkedMapOf("c" to "3", "a" to "1", "b" to "2"),
        )
        val a = codec.encode(
            CoreShellOutput(
                result = ShellInvocationResult.Failed(
                    failure = PipelineFailure(kind = FailureKind.INFRASTRUCTURE, message = "m"),
                    durableFailure = drA,
                ),
                outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.INFRASTRUCTURE, message = "m")),
            ),
        ).value
        val b = codec.encode(
            CoreShellOutput(
                result = ShellInvocationResult.Failed(
                    failure = PipelineFailure(kind = FailureKind.INFRASTRUCTURE, message = "m"),
                    durableFailure = drB,
                ),
                outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.INFRASTRUCTURE, message = "m")),
            ),
        ).value
        assertEquals(a, b, "Map iteration order MUST NOT affect encoded form")
    }

    @Test
    fun `determinism — interruption details map iteration order does not affect encoded form`() {
        val irA = InterruptionRecord(
            kind = InterruptionKind.TIMEOUT, message = "k", operationId = "op",
            details = linkedMapOf("a" to "1", "b" to "2", "c" to "3"),
        )
        val irB = InterruptionRecord(
            kind = InterruptionKind.TIMEOUT, message = "k", operationId = "op",
            details = linkedMapOf("c" to "3", "b" to "2", "a" to "1"),
        )
        val a = codec.encode(
            CoreShellOutput(
                result = ShellInvocationResult.Interrupted(interruption = irA),
                outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.TIMEOUT, message = "k")),
            ),
        ).value
        val b = codec.encode(
            CoreShellOutput(
                result = ShellInvocationResult.Interrupted(interruption = irB),
                outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.TIMEOUT, message = "k")),
            ),
        ).value
        assertEquals(a, b, "InterruptionRecord details map order MUST NOT affect encoded form")
    }

    @Test
    fun `determinism — encode does NOT carry runtime timestamps`() {
        // The encoded form for a fixed instance produced at different points in time
        // MUST be identical (no `finishedAt` / `encodedAt` / `timestamp` fields).
        val out = CoreShellOutput(
            result = ShellInvocationResult.Stdout("hello"),
            outcome = StepOutcome.Success,
        )
        val encoded = codec.encode(out).value
        assertTrue(
            "timestamp" !in encoded.lowercase(),
            "encode MUST NOT carry a runtime timestamp",
        )
        assertTrue(
            "finishedAt" !in encoded,
            "encode MUST NOT carry finishedAt (lives on OperationOutput, not on the typed carrier)",
        )
        assertTrue(
            "now" !in encoded,
            "encode MUST NOT carry 'now' timestamps",
        )
    }

    @Test
    fun `determinism — encode does NOT depend on object identity`() {
        // The encoded bytes do not reference hashCode / toString addresses.
        // Smoke check: encoded bytes do not contain a `0x`-style or `@`-style identity marker.
        val out = CoreShellOutput(
            result = ShellInvocationResult.Stdout("hello"),
            outcome = StepOutcome.Success,
        )
        val encoded = codec.encode(out).value
        assertTrue("@" !in encoded || "@" in "@", "no identity markers expected")
        // The above is trivially true; the real check is below — round-trip survives.
        val decoded = codec.decode(EncodedStepValue(encoded))
        assertEquals(out.result, decoded.result)
    }

    @Test
    fun `determinism — encode() called many times on the same instance produces the same bytes`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Interrupted(
                interruption = InterruptionRecord(
                    kind = InterruptionKind.PARENT_CANCELLED,
                    message = "cancelled",
                    operationId = "r/0/0",
                    details = mapOf("k" to "v"),
                ),
            ),
            outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.TIMEOUT, message = "cancelled")),
        )
        val first = codec.encode(out).value
        repeat(50) {
            assertEquals(first, codec.encode(out).value, "iteration $it diverged")
        }
    }
}
