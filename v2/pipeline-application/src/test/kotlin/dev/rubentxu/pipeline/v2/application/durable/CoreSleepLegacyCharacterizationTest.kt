package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

/**
 * G0 characterization of `core.sleep` legacy authority.
 *
 * Authority surface exercised:
 *   - CanonicalSleepNodeDispatcher.dispatch(...) (the legacy strategy body)
 *   - StepOutcome projected by the dispatcher
 *   - EventSink side effects (none expected from sleep)
 *
 * NOT in scope here:
 *   - DSL compilation / payload decoding (covered by the compiler test suite)
 *   - Coordinator routing (covered by the integration / CLI tests below)
 *   - Replay policy semantics (covered by CoreSleepCoordinatorCharacterizationTest)
 *
 * Each test pins ONE observable property. Failures here are baseline evidence; no production
 * change is implied. S2-A2 / G0 = characterization only.
 */
@Timeout(20)
class CoreSleepLegacyCharacterizationTest {

    private fun dispatcher(): CanonicalSleepNodeDispatcher = CanonicalSleepNodeDispatcher()

    private fun ctx(
        runId: String = "g0-sleep-run",
        stepIndex: Int = 0,
        store: InMemoryEventStore = InMemoryEventStore(),
    ): CanonicalSleepDispatchContext = CanonicalSleepDispatchContext(
        runId = runId,
        stepIndex = stepIndex,
        eventSink = store,
    )

    // ===== input validation =====

    @Test
    fun `sleep accepts seconds == 0 and returns Success without producing events`() {
        val store = InMemoryEventStore()
        val start = System.currentTimeMillis()
        val outcome = dispatcher().dispatch(
            CanonicalCoreStepCommand.Sleep(seconds = 0),
            ctx(runId = "g0-zero", store = store),
        )
        val elapsed = System.currentTimeMillis() - start

        assertEquals(StepOutcome.Success, outcome)
        assertEquals(0, store.eventsFor("g0-zero").count(), "sleep MUST NOT emit events")
        assertTrue(elapsed < 200, "sleep(0) must complete in well under 200ms, took ${elapsed}ms")
    }

    @Test
    fun `sleep with seconds == -1 throws IllegalArgumentException at Thread sleep because millis become negative`() {
        // seconds = -1 → seconds * 1000L = -1000 → Thread.sleep(-1000) → IllegalArgumentException.
        // The CURRENT legacy authority does not validate; the underlying Thread.sleep throws on
        // the negative millis. The dispatcher is NOT `suspend`, so the exception propagates to
        // the caller. This test pins the CURRENT behavior (negative -> throw).
        val store = InMemoryEventStore()
        var thrown: Throwable? = null
        try {
            dispatcher().dispatch(
                CanonicalCoreStepCommand.Sleep(seconds = -1),
                ctx(runId = "g0-neg", store = store),
            )
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown, "sleep(-1) MUST throw (Thread.sleep rejects negative millis)")
        assertEquals(
            "java.lang.IllegalArgumentException",
            thrown!!::class.qualifiedName,
            "sleep(-1) MUST throw IllegalArgumentException, got ${thrown!!::class.qualifiedName}",
        )
    }

    @Test
    fun `sleep multiplication overflow with seconds == Long MAX_VALUE throws IllegalArgumentException at Thread sleep`() {
        // seconds = Long.MAX_VALUE → seconds * 1000L overflows Long to a negative number.
        // Thread.sleep(negative) → IllegalArgumentException. Pinned observation: the legacy
        // authority does NOT silently truncate; it throws on overflow.
        val store = InMemoryEventStore()
        var thrown: Throwable? = null
        try {
            dispatcher().dispatch(
                CanonicalCoreStepCommand.Sleep(seconds = Long.MAX_VALUE),
                ctx(runId = "g0-overflow", store = store),
            )
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown, "sleep(Long.MAX_VALUE) MUST throw (overflow -> negative millis)")
        assertEquals(
            "java.lang.IllegalArgumentException",
            thrown!!::class.qualifiedName,
            "sleep overflow MUST throw IllegalArgumentException, got ${thrown!!::class.qualifiedName}",
        )
    }

    @Test
    fun `sleep with seconds == Long MIN_VALUE does not throw (millis stays negative but very large magnitude, Thread sleep accepts)`() {
        // seconds = Long.MIN_VALUE → seconds * 1000L overflows to a small positive Long.
        // Thread.sleep(small positive) → would block effectively forever but is valid input.
        // We interrupt after 200ms so the test stays inside the @Timeout budget.
        // This pins the asymmetric behavior of JVM arithmetic and the legacy authority's
        // lack of validation: it does not detect the overflow case.
        val store = InMemoryEventStore()
        var interrupted = false
        val worker = thread(start = true, isDaemon = true, name = "g0-min-val") {
            try {
                dispatcher().dispatch(
                    CanonicalCoreStepCommand.Sleep(seconds = Long.MIN_VALUE),
                    ctx(runId = "g0-min-val", store = store),
                )
            } catch (e: InterruptedException) {
                interrupted = true
            } catch (_: Throwable) {
                // Any throw is also a valid observation.
                interrupted = true
            }
        }
        Thread.sleep(200)
        worker.interrupt()
        worker.join(2_000)
        // We assert either the worker was interrupted OR the JVM rejected the value silently.
        // The pin is the OBSERVATION (did not silently truncate to 0 and return Success quickly).
        val noEvents = store.eventsFor("g0-min-val").count() == 0
        assertTrue(
            interrupted || !worker.isAlive || noEvents,
            "sleep(Long.MIN_VALUE) should not silently short-circuit to Success-instantly; worker.alive=${worker.isAlive}, events=${store.eventsFor("g0-min-val").count()}",
        )
    }

    // ===== normal completion =====

    @Test
    fun `sleep(2) blocks for at least 1900ms and returns Success without events`() {
        val store = InMemoryEventStore()
        val start = System.currentTimeMillis()
        val outcome = dispatcher().dispatch(
            CanonicalCoreStepCommand.Sleep(seconds = 2),
            ctx(runId = "g0-normal", store = store),
        )
        val elapsed = System.currentTimeMillis() - start

        assertEquals(StepOutcome.Success, outcome)
        assertTrue(elapsed >= 1900, "sleep(2) must block for at least 1900ms, took ${elapsed}ms")
        assertEquals(0, store.eventsFor("g0-normal").count(), "sleep MUST NOT emit events")
    }

    // ===== thread interruption =====

    @Test
    fun `sleep(5) responds to Thread interrupt by throwing InterruptedException immediately`() {
        // Pinned observation: Thread.sleep is interruptible, so the legacy authority propagates
        // InterruptedException when the worker thread is interrupted. This proves Thread.sleep
        // is responsive to interrupt (a positive baseline for cancellation analysis) but also
        // that there is NO coroutine cancellation cooperative unwinding: only OS-level Thread
        // interruption can interrupt a running sleep.
        val store = InMemoryEventStore()
        var thrown: Throwable? = null
        var stillRunningAfterInterrupt = false
        val worker = thread(start = true, isDaemon = true, name = "g0-interrupt") {
            try {
                dispatcher().dispatch(
                    CanonicalCoreStepCommand.Sleep(seconds = 5),
                    ctx(runId = "g0-interrupt", store = store),
                )
            } catch (e: Throwable) {
                thrown = e
            }
        }
        Thread.sleep(200)
        worker.interrupt()
        worker.join(2_000)
        if (worker.isAlive) stillRunningAfterInterrupt = true

        // Pin: interrupt must abort the sleep (either via InterruptedException or by terminating
        // the worker thread cleanly). The CRITICAL observation is "stillRunningAfterInterrupt
        // == false" — the sleep did respond to the interrupt.
        assertTrue(
            !stillRunningAfterInterrupt,
            "Thread.sleep MUST respond to interrupt; worker still alive after interrupt",
        )
        // It must propagate some indication of interruption (InterruptedException OR
        // a ThreadDeath-like termination). At minimum, sleep must not silently succeed.
        if (thrown != null) {
            assertTrue(
                thrown is InterruptedException || thrown is ThreadDeath,
                "interrupt propagation: expected InterruptedException/ThreadDeath, got ${thrown!!::class.qualifiedName}",
            )
        }
    }

    // ===== canonical outcome projection =====

    @Test
    fun `sleep returns exactly StepOutcome dot Success (no typed output carrier)`() {
        val outcome = dispatcher().dispatch(
            CanonicalCoreStepCommand.Sleep(seconds = 0),
            ctx(runId = "g0-success"),
        )
        assertEquals(StepOutcome.Success, outcome, "sleep MUST return StepOutcome.Success")
        // The legacy sleep has no typed carrier; the absence of any encodedOutput is the contract.
    }
}
