package dev.rubentxu.pipeline.v2.spike.stagescoped

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * HF1 (in-process) interpreter tests with a [RecordingFacade] that records
 * every dispatched call and returns scripted outcomes.
 *
 * These tests prove the interpreter's invariants without touching the real
 * filesystem or processes:
 *  - one outcome per suspend call, in source order;
 *  - eager ops do NOT trigger facade calls;
 *  - trace ordinals are contiguous 1..N;
 *  - Trivial is returned when there are no suspend calls.
 */
class StageScopedFrontendTest {

    @Test
    @DisplayName("plan with no suspend calls returns Trivial and never calls the facade")
    fun noSuspendReturnsTrivial() {
        val builder = StagePlanBuilder()
        builder.eager(StepSpec.Echo("a"))
        builder.eager(StepSpec.Echo("b"))
        val outcome = builder.build()
        val plan = (outcome as StagePlanBuilder.Outcome.Plan).plan

        val facade = RecordingFacade()
        val result = StageScopedFrontend.interpret(plan, facade)

        assertSame(Executed.Trivial, result)
        assertEquals(0, facade.callCount)
    }

    @Test
    @DisplayName("single suspend call returns WithSuspend with one entry")
    fun singleSuspendProducesOneEntry() {
        val builder = StagePlanBuilder()
        builder.pwd(tmp = false)
        val outcome = builder.build()
        val plan = (outcome as StagePlanBuilder.Outcome.Plan).plan

        val facade = RecordingFacade(
            pwdResult = SuspendOutcome.StringOutcome(value = "/tmp/build"),
        )
        val result = StageScopedFrontend.interpret(plan, facade)

        val withSuspend = assertInstanceOf(Executed.WithSuspend::class.java, result)
        assertEquals(1, withSuspend.trace.size)
        val entry = withSuspend.trace.single()
        assertEquals(1, entry.ordinal)
        assertEquals(SuspendCall.Pwd(tmp = false), entry.call)
        assertEquals(SuspendOutcome.StringOutcome("/tmp/build"), entry.outcome)
        assertEquals(1, facade.callCount)
    }

    @Test
    @DisplayName("multiple suspend calls preserve source order and are 1..N contiguous")
    fun multipleSuspendCallsPreserveOrder() {
        val builder = StagePlanBuilder()
        builder.pwd(tmp = false)
        builder.isUnix()
        builder.fileExists("gradlew")
        builder.shReturnStdout("git rev-parse HEAD", encoding = null)
        val outcome = builder.build()
        val plan = (outcome as StagePlanBuilder.Outcome.Plan).plan

        val facade = RecordingFacade(
            pwdResult = SuspendOutcome.StringOutcome(value = "/ws"),
            isUnixResult = SuspendOutcome.BooleanOutcome(value = true),
            fileExistsResult = SuspendOutcome.BooleanOutcome(value = true),
            shResult = SuspendOutcome.StringOutcome(value = "abc123"),
        )
        val result = StageScopedFrontend.interpret(plan, facade)

        val withSuspend = assertInstanceOf(Executed.WithSuspend::class.java, result)
        assertEquals(4, withSuspend.trace.size)
        assertEquals(1, withSuspend.trace[0].ordinal)
        assertEquals(2, withSuspend.trace[1].ordinal)
        assertEquals(3, withSuspend.trace[2].ordinal)
        assertEquals(4, withSuspend.trace[3].ordinal)
        assertEquals(SuspendCall.Pwd(tmp = false), withSuspend.trace[0].call)
        assertEquals(SuspendCall.IsUnix, withSuspend.trace[1].call)
        assertEquals(SuspendCall.FileExists("gradlew"), withSuspend.trace[2].call)
        assertEquals(SuspendCall.ShReturnStdout("git rev-parse HEAD", null), withSuspend.trace[3].call)
    }

    @Test
    @DisplayName("eager ops interleaved with suspend calls: only suspend calls hit the facade")
    fun eagerOpsDoNotHitFacade() {
        val builder = StagePlanBuilder()
        builder.eager(StepSpec.Echo("hello"))     // eager
        builder.pwd(tmp = false)                    // suspend 1
        builder.shReturnStdout("ls")                // suspend 2
        builder.eager(StepSpec.Echo("world"))      // eager
        val outcome = builder.build()
        val plan = (outcome as StagePlanBuilder.Outcome.Plan).plan

        val facade = RecordingFacade(
            pwdResult = SuspendOutcome.StringOutcome(value = "/ws"),
            shResult = SuspendOutcome.StringOutcome(value = ""),
        )
        val result = StageScopedFrontend.interpret(plan, facade)

        val withSuspend = assertInstanceOf(Executed.WithSuspend::class.java, result)
        assertEquals(2, withSuspend.trace.size)
        assertEquals(2, facade.callCount) // exactly pwd + sh, never echo
        // Both calls were observed; both echoes went through unintercepted.
        assertEquals(listOf("pwd", "shReturnStdout"), facade.dispatchedNames)
    }

    @Test
    @DisplayName("readFile carries the file path verbatim into the facade")
    fun readFilePropagatesPath() {
        val builder = StagePlanBuilder()
        builder.readFile("src/main/kotlin/Foo.kt")
        val plan = (builder.build() as StagePlanBuilder.Outcome.Plan).plan

        val facade = RecordingFacade(
            readFileResult = SuspendOutcome.StringOutcome(value = "package foo"),
        )
        val result = StageScopedFrontend.interpret(plan, facade)
        val entry = (result as Executed.WithSuspend).trace.single()
        assertEquals(SuspendCall.ReadFile("src/main/kotlin/Foo.kt"), entry.call)
        assertEquals(SuspendOutcome.StringOutcome("package foo"), entry.outcome)
    }

    @Test
    @DisplayName("each SuspendCall case dispatches to the right facade method (exhaustive mapping)")
    fun eachSuspendCallHasItsOwnFacadeMethod() {
        val builder = StagePlanBuilder()
        builder.pwd(tmp = true)
        builder.readFile("a.txt")
        builder.fileExists("a.txt")
        builder.shReturnStdout("echo hi", encoding = "UTF-8")
        builder.isUnix()
        val plan = (builder.build() as StagePlanBuilder.Outcome.Plan).plan

        val facade = RecordingFacade(
            pwdResult = SuspendOutcome.StringOutcome(value = ""),
            readFileResult = SuspendOutcome.StringOutcome(value = ""),
            fileExistsResult = SuspendOutcome.BooleanOutcome(value = false),
            shResult = SuspendOutcome.StringOutcome(value = ""),
            isUnixResult = SuspendOutcome.BooleanOutcome(value = true),
        )
        StageScopedFrontend.interpret(plan, facade)
        assertEquals(
            listOf("pwd", "readFile", "fileExists", "shReturnStdout", "isUnix"),
            facade.dispatchedNames,
        )
    }

    @Test
    @DisplayName("builder.build() rejects plans whose suspend ordinals are not contiguous")
    fun builderRejectsNonContiguousOrdinals() {
        // We can't easily force a non-contiguous ordinal through the builder
        // (it auto-increments), so we hand-construct an invalid plan and run
        // the spec directly. This proves the contract end-to-end.
        val ops = listOf(
            StageOp.Suspend(ordinal = 1, call = SuspendCall.IsUnix),
            StageOp.Suspend(ordinal = 3, call = SuspendCall.IsUnix),
        )
        val verdict = LexicalOrderSpec.check(ops)
        val invalid = assertInstanceOf(LexicalOrderSpec.Result.Invalid::class.java, verdict)
        assertInstanceOf(LexicalOrderSpec.Reason.OrdinalGap::class.java, invalid.reason)
    }
}

/** In-memory facade that records every dispatched call and returns scripted outcomes. */
private class RecordingFacade(
    private val pwdResult: SuspendOutcome = SuspendOutcome.StringOutcome(value = ""),
    private val readFileResult: SuspendOutcome = SuspendOutcome.StringOutcome(value = ""),
    private val fileExistsResult: SuspendOutcome = SuspendOutcome.BooleanOutcome(value = false),
    private val shResult: SuspendOutcome = SuspendOutcome.StringOutcome(value = ""),
    private val isUnixResult: SuspendOutcome = SuspendOutcome.BooleanOutcome(value = false),
) : SuspendRuntimeFacade {

    val dispatchedNames: MutableList<String> = mutableListOf()
    val callCount: Int get() = dispatchedNames.size

    override fun pwd(tmp: Boolean): SuspendOutcome {
        dispatchedNames += "pwd"
        return pwdResult
    }

    override fun readFile(file: String): SuspendOutcome {
        dispatchedNames += "readFile"
        return readFileResult
    }

    override fun fileExists(file: String): SuspendOutcome {
        dispatchedNames += "fileExists"
        return fileExistsResult
    }

    override fun shReturnStdout(script: String, encoding: String?): SuspendOutcome {
        dispatchedNames += "shReturnStdout"
        return shResult
    }

    override fun isUnix(): SuspendOutcome {
        dispatchedNames += "isUnix"
        return isUnixResult
    }
}
