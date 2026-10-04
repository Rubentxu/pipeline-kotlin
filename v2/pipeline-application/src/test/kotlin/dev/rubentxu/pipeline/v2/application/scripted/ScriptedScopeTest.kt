package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CoreShellOutput
import dev.rubentxu.pipeline.v2.application.CoreShellStep
import dev.rubentxu.pipeline.v2.application.SHELL_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.ShellOperations
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.serialization.json.jsonPrimitive
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.ShellExitException
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ReturnStatus
import dev.rubentxu.pipeline.v2.scripting.ReturnStdout
import dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult
import dev.rubentxu.pipeline.v2.scripting.ScriptEvaluationOutput
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import dev.rubentxu.pipeline.v2.application.support.settled

@Timeout(10)
class ScriptedScopeTest {

    /**
     * S4-A1 — a registry invoker over a STUB `ShellOperations`, plus its journal.
     *
     * `sh` is a runtime-returning scripted call like `pwd` or `isUnix`, so it now
     * reaches the durable engine through `ScriptedRegistryInvoker` and needs an
     * invoker wired. Production always has one — `ScriptedFrontendRunner`
     * constructs it unconditionally — so a `ScriptedArtifactRuntime` built without
     * one no longer represents how a real run is composed, and these tests must
     * stop modelling that.
     *
     * The stub satisfies `core.sh` through its declared `SHELL_OPERATIONS_CAPABILITY`
     * rather than by bypassing it, so the admission path these tests now exercise
     * is the real one: the capability is asked for, and the Step runs only because
     * it was granted.
     *
     * The journal is returned because the scope-path assertions now read the DURABLE
     * record instead of an in-memory side channel the old eager runtime happened to
     * pass through. That is a stronger assertion, not a workaround: it proves the
     * identities written to the journal are the distinct ones, which is the property
     * replay actually depends on.
     */
    /**
     * `resultFor` comes FIRST on purpose: `onLaunch` must stay last so the existing
     * `shellSpine { launches += 1 }` trailing-lambda call sites keep binding to it. Putting the
     * new parameter last would silently rebind them to `resultFor` and fail on the return type —
     * a harness change that breaks callers it never touched.
     */
    private fun shellSpine(
        resultFor: (dev.rubentxu.pipeline.v2.domain.ShellCommand) -> ShellInvocationResult = { command ->
            if (command.script == "branch") {
                ShellInvocationResult.Stdout("main\n")
            } else {
                ShellInvocationResult.UnitValue
            }
        },
        onLaunch: () -> Unit = {},
    ): Triple<ScriptedRegistryInvoker, InMemoryOperationJournal, InMemoryStepRegistry> {
        val journal = InMemoryOperationJournal(SystemClock())
        val registry = InMemoryStepRegistry().also { CoreShellStep.registerInto(it) }
        val invoker = dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
            registry = registry,
            journal = journal,
        capabilityAccessFactory = { context ->
                        object : CanonicalRuntimeCapabilityAccess(context) {
                            override fun available(): Set<StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)
        
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : Any> get(key: StepCapability): T {
                                if (key == SHELL_OPERATIONS_CAPABILITY) {
                                    return object : ShellOperations {
                                        override suspend fun invoke(
                                            command: dev.rubentxu.pipeline.v2.domain.ShellCommand,
                                            runId: dev.rubentxu.pipeline.v2.domain.RunId,
                                            stepIndex: Int,
                                        ): ShellInvocationResult {
                                            onLaunch()
                                            return resultFor(command)
                                        }
                                    } as T
                                }
                                return super.get(key)
                            }
                        }
                    },
    )
        return Triple(invoker, journal, registry)
    }

    /** Call-site ids of every durable operation written for [runId], in insertion order. */
    private fun InMemoryOperationJournal.callSitesOf(runId: String): List<String> =
        listForRun(runId).map { it.input.params["callSiteId"]?.jsonPrimitive?.content.orEmpty() }

    /**
     * Dynamic scope paths of every durable operation written for [runId], in
     * insertion order.
     *
     * Returned as the RAW joined string rather than a list: a scope id may itself
     * contain `/` (`retry:deploy/attempt:1`), so splitting on the separator would
     * fragment a single scope into pieces and assert something the runtime never
     * promised.
     */
    private fun InMemoryOperationJournal.scopePathsOf(runId: String): List<String> =
        listForRun(runId).map { op ->
            op.input.params["dynamicScopePath"]?.jsonPrimitive?.content.orEmpty()
        }

    @Test
    fun `returnStatus exposes a nonzero shell exit as an Int`() = runBlocking {
        val operations = mutableListOf<ScriptedOperation>()
        val runtime = ScriptedRuntime(
            operationRuntime = ScriptedOperationRuntime { operation ->
                operations += operation
                settled(ShellInvocationResult.Status(42))
            },
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/sh-status"),
        )

        val status: Int = runtime.run(definitionDigest = "test-v1", entryPointId = "main") {
            sh(script = "exit 42", returnStatus = ReturnStatus)
        }

        assertEquals(42, status)
        assertEquals("scripted-test/sh-status", operations.single().callSiteId.value)
        assertEquals(ShellReturnMode.STATUS, operations.single().command.returnMode)
    }

    @Test
    fun `returnStdout exposes stdout as a String`() = runBlocking {
        val runtime = ScriptedRuntime(
            operationRuntime = ScriptedOperationRuntime { settled(ShellInvocationResult.Stdout("main\n")) },
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/sh-stdout"),
        )

        val stdout: String = runtime.run(definitionDigest = "test-v1", entryPointId = "main") {
            sh(script = "printf main", returnStdout = ReturnStdout)
        }

        assertEquals("main\n", stdout)
    }

    @Test
    fun `default shell failure throws the typed shell exception`() = runBlocking {
        val runtime = ScriptedRuntime(
            operationRuntime = ScriptedOperationRuntime {
                settled(ShellInvocationResult.Failed(PipelineFailure(FailureKind.SCRIPT, "shell exited with code 7")))
            },
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/sh-failure"),
        )

        val failure = runCatching {
            runtime.run(definitionDigest = "test-v1", entryPointId = "main") { sh(script = "exit 7") }
        }.exceptionOrNull()

        assertEquals(ShellExitException::class, failure?.javaClass?.kotlin)
    }

    /**
     * S4-F1-B — MIGRATED to the canonical authority.
     *
     * This used to run on `JournaledScriptedOperationRuntime`, a second durable stack that owned
     * its own fingerprint, its own replay table and its own status mapping, and that production
     * never constructed. Asserting a law THROUGH it certified that stack rather than the product:
     * the canonical scripted path could have disagreed about reuse entirely and this test would
     * have stayed green. The same law, asserted through `ScriptedRegistryInvoker`, cannot.
     */
    @Test
    fun `completed scripted status is replayed without reinvoking the effect`() = runBlocking {
        var launches = 0
        val (invoker, _, registry) = shellSpine(
            onLaunch = { launches += 1 },
            resultFor = { ShellInvocationResult.Status(42) },
        )
        val runtime = ScriptedRuntime(
            operationRuntime = RegistryScriptedShellRuntime(invoker),
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/replayed-status"),
        )

        suspend fun invokeStatus(): Int = runtime.run(definitionDigest = "test-v1", entryPointId = "main") {
            sh(script = "exit 42", returnStatus = ReturnStatus)
        }

        assertEquals(42, invokeStatus())
        assertEquals(42, invokeStatus())
        assertEquals(1, launches)
    }

    /** S4-F1-B — MIGRATED, same reason as the row above. */
    @Test
    fun `changed scripted input fails closed without reinvoking the effect`() = runBlocking {
        var launches = 0
        val (invoker, _, registry) = shellSpine(
            onLaunch = { launches += 1 },
            resultFor = { ShellInvocationResult.Status(0) },
        )
        val runtime = ScriptedRuntime(
            operationRuntime = RegistryScriptedShellRuntime(invoker),
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/divergence"),
        )

        suspend fun invokeStatus(script: String): Int = runtime.run(definitionDigest = "test-v1", entryPointId = "main") {
            sh(script = script, returnStatus = ReturnStatus)
        }

        assertEquals(0, invokeStatus("exit 0"))
        val failure = runCatching { invokeStatus("exit 7") }.exceptionOrNull() as PipelineStepException
        assertEquals(FailureKind.REPLAY_COMPATIBILITY, failure.failure.kind)
        assertEquals(1, launches)
    }

    /**
     * S4-F1-B — REPLACES the retired `running durable shell reattaches without relaunching the
     * effect` row, and asserts a STRICTLY STRONGER claim.
     *
     * The retired row launched a real durable process, detached it, and recovered it through
     * `DurableScriptedOperationReconciler` — the scripted recovery path that production never
     * constructs. It asserted the scripted surface could return a typed `42` from a recovered
     * terminal, through an authority that does not exist in production. That much was fiction.
     *
     * **What is NOT fiction, and what this row must not be read as saying:** the current canonical
     * spine does not materialise a typed value from a recovered terminal, and that is not a law —
     * it is a gap. The substrate preserves enough facts for *some* return modes. This very row
     * launches a `returnStatus` shell that exits 42, so the exit code is an OBSERVED fact, not a
     * fabricated one, and `sh(returnStatus = true)` with exit 42 is `Status(42) · Success` under
     * the contract that already exists (`classifyShellTerminal` → `ShellStepOutcomeClassifier`).
     * Today that evidence is narrowed before it reaches the Step-specific projection. That is
     * manifestation 2 of `implementation conformance: PARTIAL` in ADR-S4-R1, and S4-F1-C owns its
     * closure under §2.7.
     *
     * The distinction this row turns on is the one that separates fabrication from conservation:
     *
     * ```text
     * LOST / no facts observed    → failing closed is CORRECT (no `42` exists to hand)
     * Exited(42) / exit code seen → failing closed is a GAP      (a `42` was observed)
     * ```
     *
     * The observation injected below is deliberately `Lost`, so this row certifies the correct case
     * and says out loud which case it is not certifying. A row that injected `Exited(42)` and
     * asserted the same typed failure would be certifying a defect as desired behaviour.
     *
     * Two things replace the retired row, and both are true where the old row was fiction:
     *
     * 1. The REATTACH-NOT-RELAUNCH law is certified canonically by `S4-R-REC` rows 4-10, which
     *    drive the real coordinator and assert the handler does not run.
     * 2. The scripted behaviour on a recovered terminal is now asserted HERE: recovery happens,
     *    nothing is relaunched, and a terminal with no value facts fails closed rather than
     *    inventing one.
     *
     * The observation is scripted rather than real, which is legitimate HERE and was not in the old
     * row: the property under test is what the scripted surface does WITH a recovery resolution,
     * and that is decided by the invoker. Constructing real processes would have tested the
     * observer again, not the invoker.
     *
     * The first cut of this row failed with `launches == 1` and the failure was MINE, not the
     * product's: recovery is only ever consulted for a journal row that is `RUNNING`, and the
     * fixture started from an empty journal, so the resolver correctly decided the operation was
     * FRESH and executed it. Substituting the observer does not make recovery apply — it makes
     * recovery *observable* once something leaves a `RUNNING` row behind. The row now creates that
     * row first, which is the state a crashed worker actually leaves.
     */
    @Test
    fun `a recovered terminal is recovered rather than relaunched, and no typed value is fabricated`() = runBlocking {
        var launches = 0
        val journal = InMemoryOperationJournal(SystemClock())
        val registry = InMemoryStepRegistry().also { CoreShellStep.registerInto(it) }
        val capabilityAccessFactory = { context: CanonicalRuntimeContext ->
            object : CanonicalRuntimeCapabilityAccess(context) {
                override fun available(): Set<StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: StepCapability): T {
                    if (key == SHELL_OPERATIONS_CAPABILITY) {
                        return object : ShellOperations {
                            override suspend fun invoke(
                                command: dev.rubentxu.pipeline.v2.domain.ShellCommand,
                                runId: dev.rubentxu.pipeline.v2.domain.RunId,
                                stepIndex: Int,
                            ): ShellInvocationResult {
                                launches += 1
                                // The scripted call asks for `returnStatus`, so the substrate must
                                // answer with a Status. Returning UnitValue here is not a harness
                                // convenience — the engine rejects it with
                                // `EngineInvariantViolation: Shell runtime returned UnitValue for
                                // STATUS mode`, which is the correct fail-closed behaviour and the
                                // reason this stub states the mode it claims to satisfy.
                                return ShellInvocationResult.Status(42)
                            }
                        } as T
                    }
                    return super.get(key)
                }
            }
        }
        val callSites = ScriptedCallSiteProvider.fixed("scripted-test/recovered-terminal")

        // 1. Run it once through the canonical path so a real durable row exists.
        val executing = dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
            registry = registry,
            journal = journal,
            capabilityAccessFactory = capabilityAccessFactory,
        )
        ScriptedRuntime(RegistryScriptedShellRuntime(executing), callSites)
            .run(definitionDigest = "test-v1", entryPointId = "main") {
                sh(script = "exit 42", returnStatus = ReturnStatus)
            }
        assertEquals(1, launches, "PRECONDITION: the first run executes exactly once")

        // 2. Leave the row RUNNING — the state a worker that died mid-effect leaves behind.
        val source = journal.listForRun("test-v1").single()
        journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = source.id,
                fingerprint = source.fingerprint,
                input = source.input,
                output = null,
                status = dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.RUNNING,
                attempt = source.attempt,
            ),
        )

        // 3. The same call again. Recovery is now REQUIRED, and the substrate observation is
        //    scripted — the ONE thing this harness substitutes. The resolver still decides, and
        //    the interpreter still journals the terminal.
        val recovering = dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
            registry = registry,
            journal = journal,
            capabilityAccessFactory = capabilityAccessFactory,
            runningSubprocessRecovery = {
                dev.rubentxu.pipeline.v2.application.durable.RunningSubprocessObservation.Observed(
                    dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal.Lost(
                        dev.rubentxu.pipeline.v2.domain.durable.FailureRecord(
                            code = "REATTACH_WINDOW_EXPIRED",
                            kind = FailureKind.INFRASTRUCTURE,
                            message = "reattach window closed",
                            origin = dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin.RECONCILIATION,
                            retryable = false,
                            operationId = "scripted-test/recovered-terminal",
                        ),
                    ),
                )
            },
        )
        val failure = runCatching {
            ScriptedRuntime(RegistryScriptedShellRuntime(recovering), callSites)
                .run(definitionDigest = "test-v1", entryPointId = "main") {
                    sh(script = "exit 42", returnStatus = ReturnStatus)
                }
        }.exceptionOrNull()

        assertEquals(
            1,
            launches,
            "the effect is NEVER relaunched on a recovered terminal — recovery, not a second launch",
        )
        assertTrue(
            failure is PipelineStepException,
            "and the scripted surface fails TYPED. NOTE WHICH TERMINAL THIS ROW INJECTS: a LOST " +
                "terminal, which carries NO value facts — no exit code was ever observed, so " +
                "failing closed here is correct and `Status(0)` would be fabrication. This row " +
                "does NOT certify that a recovered `returnStatus` can never yield its exit code: " +
                "an `Exited(42)` terminal DOES carry that fact, and the current spine narrowing it " +
                "before the Step-owned projection is the canonical gap F1-C owns (ADR-S4-R1 §2.7). " +
                "Got $failure",
        )
    }

    /**
     * S4-F1-C2/C3 — the row that CERTIFIES the gap the row above only DECLARES.
     *
     * That row injects a LOST terminal, so failing closed is the correct answer, and its KDoc says
     * so explicitly: it does not certify what a recovered `returnStatus` yields. This row injects
     * the other kind of terminal — `Exited(42)`, which carries an OBSERVED exit code — and asserts
     * the claim R14 exists for: that the typed value survives the whole spine and reaches user
     * Kotlin, in a run where the process is never relaunched.
     *
     * ## Why this row exists at the CONSUMER boundary, and not at the journal
     *
     * Not by preference — because the mutation proved it. M-F1-C3 narrows the carrier that
     * `RecoveryInterpretationEngine` hands back to a bare `StepOutcome`, dropping `encodedOutput`
     * while leaving the journalled bytes untouched. Measured, not assumed:
     *
     * ```text
     * S4R1F1CRecoveryTruthMatrixTest   17/17 GREEN under the mutation   (it reads the journal row)
     * S4RKernelSpikeTest               8/8  GREEN under the mutation
     * S4RRecIndeterminateEffectSpikeTest 12/12 GREEN under the mutation
     * ScriptedScopeTest                13/13 GREEN under the mutation   (before this row)
     * ```
     *
     * The value is journalled correctly and simply never reaches the program. This row crosses the
     * boundary where that is observable — `ScriptedRegistryInvoker.invoke` reads
     * `settled.result.encodedOutput` — and under the mutation it fails closed with
     * `FailureKind.ENGINE`, which is why it kills it.
     *
     * ## Harness
     *
     * `behavioural`. Production entry points: `ScriptedRegistryInvoker.invoke` and the real
     * `RecoveryInterpretationEngine`. The ONE substituted thing is the substrate observation, as in
     * the row above; launches are counted at the handler capability.
     */
    @Test
    fun `a recovered exit code reaches user Kotlin as the exit code it observed`(
        @TempDir recoveredControlDir: Path,
    ) = runBlocking {
        var launches = 0
        val journal = InMemoryOperationJournal(SystemClock())
        val registry = InMemoryStepRegistry().also { CoreShellStep.registerInto(it) }
        val capabilityAccessFactory = { context: CanonicalRuntimeContext ->
            object : CanonicalRuntimeCapabilityAccess(context) {
                override fun available(): Set<StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: StepCapability): T {
                    if (key == SHELL_OPERATIONS_CAPABILITY) {
                        return object : ShellOperations {
                            override suspend fun invoke(
                                command: dev.rubentxu.pipeline.v2.domain.ShellCommand,
                                runId: dev.rubentxu.pipeline.v2.domain.RunId,
                                stepIndex: Int,
                            ): ShellInvocationResult {
                                launches += 1
                                return ShellInvocationResult.Status(42)
                            }
                        } as T
                    }
                    return super.get(key)
                }
            }
        }
        val callSites = ScriptedCallSiteProvider.fixed("scripted-test/recovered-value")

        // 1. A real durable row, produced by a real execution.
        val executing = dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
            registry = registry,
            journal = journal,
            capabilityAccessFactory = capabilityAccessFactory,
        )
        ScriptedRuntime(RegistryScriptedShellRuntime(executing), callSites)
            .run(definitionDigest = "test-v1", entryPointId = "main") {
                sh(script = "exit 42", returnStatus = ReturnStatus)
            }
        assertEquals(1, launches, "PRECONDITION: the first run executes exactly once")

        // 2. Leave the row RUNNING — the state a worker that died mid-effect leaves behind.
        val source = journal.listForRun("test-v1").single()
        journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = source.id,
                fingerprint = source.fingerprint,
                input = source.input,
                output = null,
                status = dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.RUNNING,
                attempt = source.attempt,
            ),
        )

        // 3. Recover from a terminal that CARRIES facts. This is the whole difference from the row
        //    above: the exit code was observed, it is on disk, and nobody has to invent it.
        val recovering = dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
            registry = registry,
            journal = journal,
            capabilityAccessFactory = capabilityAccessFactory,
            runningSubprocessRecovery = {
                dev.rubentxu.pipeline.v2.application.durable.RunningSubprocessObservation.Observed(
                    DurableTaskTerminal.Exited(
                        exitCode = 42,
                        output = DurableTaskOutput(controlDir = recoveredControlDir.toString()),
                    ),
                )
            },
        )
        val recovered = runCatching {
            ScriptedRuntime(RegistryScriptedShellRuntime(recovering), callSites)
                .run(definitionDigest = "test-v1", entryPointId = "main") {
                    sh(script = "exit 42", returnStatus = ReturnStatus)
                }
        }

        assertEquals(
            1,
            launches,
            "the effect is NEVER relaunched on a recovered terminal — recovery, not a second launch",
        )
        val failure = recovered.exceptionOrNull()
        assertTrue(
            failure == null,
            "a recovered terminal that CARRIES an exit code must not fail closed. The 42 was " +
                "observed in the control directory, `returnStatus` was declared, and the Step's own " +
                "contract says a non-zero exit under STATUS is a VALUE, not an error — so this " +
                "invocation owes the program that 42. Failing here is the R14 loss in its " +
                "consumer-facing form: the carrier arrived without the value, and the invoker " +
                "reported ENGINE rather than delivering 42. Got $failure",
        )
        assertEquals(
            42,
            recovered.getOrNull(),
            "THE ACTUAL CLAIM: user Kotlin receives the exit code that was observed, in a run " +
                "where the process was never relaunched. A 0 here would mean the value was " +
                "defaulted rather than transported, and it is the single assertion that R14 " +
                "cannot be quietly undone without turning this row red.",
        )
    }

    /**
     * S4-F1-B — MIGRATED to the canonical authority, and this one gets STRONGER.
     *
     * `CoreShellCodec` encodes and decodes `durableFailure` on the canonical durable wire, so the
     * provenance this test cares about is carried by the product and not by the retired stack. The
     * migrated row therefore proves a stronger claim than the original, and it proves it in a
     * stronger PLACE.
     *
     * The original read `durableFailure` off the value the runtime handed back, which was decoded by
     * `JournaledScriptedOperationRuntime`'s own JSON reader. That reader existed only in the retired
     * class, so the row certified a serialisation nothing else in the repository could produce or
     * consume. The migrated row reads the provenance back out of the **journal row's encoded
     * output**, decoded by the same `CoreShellCodec` a real `core.sh` uses. That is what "survives
     * terminal replay" has to mean if it is to mean anything durable: the record is on the wire, not
     * in a value that only this stack can build.
     *
     * The call itself throws, because a failed `core.sh` fails the Step — that is correct product
     * behaviour, and the first cut of this row mistook it for a migration defect. The provenance is
     * in the durable record, so the assertion reads the record.
     */
    @Test
    fun `durable failure provenance survives terminal replay`() = runBlocking {
        val provenance = FailureRecord(
            code = "DURABLE_TASK_LOST",
            kind = FailureKind.INFRASTRUCTURE,
            message = "worker disappeared",
            origin = FailureOrigin.RECONCILIATION,
            retryable = false,
            operationId = "scripted-test/provenance",
            details = mapOf("controlDir" to "/tmp/control"),
        )
        var launches = 0
        val (invoker, journal, registry) = shellSpine(
            onLaunch = { launches += 1 },
            resultFor = {
                ShellInvocationResult.Failed(
                    failure = PipelineFailure(FailureKind.INFRASTRUCTURE, "worker disappeared"),
                    durableFailure = provenance,
                )
            },
        )
        val runtime = ScriptedRuntime(
            operationRuntime = RegistryScriptedShellRuntime(invoker),
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/provenance"),
        )

        val failure = runCatching {
            runtime.run(definitionDigest = "test-v1", entryPointId = "main") {
                sh(script = "exit 1", returnStatus = ReturnStatus)
            }
        }.exceptionOrNull()
        assertTrue(
            failure is PipelineStepException,
            "a failed shell fails the Step, and the provenance is in the DURABLE record rather than " +
                "in a returned value. Got $failure",
        )

        // The record, decoded by the product's OWN codec — taken from the registry CONTRACT, not a
        // private reference and not a re-implementation. The cast is about the static type only; if
        // the definition were not `core.sh`'s, the decode and the assertions below would fail rather
        // than quietly pass.
        val output = journal.listForRun("test-v1").single().output
        assertTrue(
            output != null,
            "the failed terminal still persisted its encoded output, which is where the " +
                "provenance lives. A `null` here would mean the wire lost information the value " +
                "object still had — the R14 defect, one layer down.",
        )
        val definition = registry.definition(CoreShellStep.KEY)
        assertTrue(
            definition != null,
            "PRECONDITION: `core.sh` is registered, so its codec is reachable from the registry " +
                "contract rather than hardcoded here.",
        )
        @Suppress("UNCHECKED_CAST")
        val contract = (definition as StepDefinition<Any, CoreShellOutput>).contract
        val decoded = contract.outputCodec.decode(
            EncodedStepValue(output!!.result.jsonPrimitive.content),
        )
        val decodedFailure = decoded.result as ShellInvocationResult.Failed
        assertEquals(
            provenance,
            decodedFailure.durableFailure,
            "the provenance round-trips through the canonical durable wire: encoded by the " +
                "product's codec on the way out, decoded by the same codec on the way back. This " +
                "is the claim the retired row could not make — its reader existed only inside the " +
                "class being deleted.",
        )
        assertEquals(
            1,
            launches,
            "and the effect ran once: reading the record is replay, not a second launch.",
        )
    }

    @Test
    fun `compiled entry point replays explicit source call sites without reinvoking effects`() = runBlocking {
        val (invoker, journal, _) = shellSpine()
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = ScriptedOperationRuntime { error("sh is registry-routed") },
            registryInvoker = invoker,
        )
        val entryPoint = object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity(
                sourceDigest = "source-v1",
                dslApiVersion = "dsl-v1",
                compilerAdapterVersion = "compiler-v1",
                runtimeCompatibilityVersion = "runtime-v1",
                pluginLockDigest = "plugins-v1",
                facadeSchemaDigest = "facades-v1",
            )
            override val entryPointId = "deploy-main"

            override suspend fun execute(steps: ScriptedStepFacade) {
                val branch = steps.sh(
                    callSite = ScriptedCallSiteId("Pipeline.kts:10:branch"),
                    script = "branch",
                    returnStdout = ReturnStdout,
                ).trim()
                if (branch == "main") {
                    steps.sh(
                        callSite = ScriptedCallSiteId("Pipeline.kts:13:deploy"),
                        script = "deploy",
                    )
                }
            }
        }

        runtime.execute(runId = "run-001", entryPoint = entryPoint)
        runtime.execute(runId = "run-001", entryPoint = entryPoint)

        // Read from the DURABLE record rather than an in-memory launch counter: the
        // property under test is that the second execution reused the persisted
        // operations instead of launching again, and that is what the journal shows.
        assertEquals(listOf("Pipeline.kts:10:branch", "Pipeline.kts:13:deploy"), journal.callSitesOf("run-001"))
    }

    @Test
    fun `compiled artifact mismatch fails closed before relaunching an effect`() = runBlocking {
        var launches = 0
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = ScriptedOperationRuntime { error("sh is registry-routed") },
            registryInvoker = shellSpine { launches += 1 }.first,
        )
        fun entryPoint(sourceDigest: String) = object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity(
                sourceDigest = sourceDigest,
                dslApiVersion = "dsl-v1",
                compilerAdapterVersion = "compiler-v1",
                runtimeCompatibilityVersion = "runtime-v1",
                pluginLockDigest = "plugins-v1",
                facadeSchemaDigest = "facades-v1",
            )
            override val entryPointId = "deploy-main"

            override suspend fun execute(steps: ScriptedStepFacade) {
                steps.sh(
                    callSite = ScriptedCallSiteId("Pipeline.kts:20:deploy"),
                    script = "deploy",
                )
            }
        }

        runtime.execute(runId = "run-002", entryPoint = entryPoint("source-v1"))
        val failure = runCatching {
            runtime.execute(runId = "run-002", entryPoint = entryPoint("source-v2"))
        }.exceptionOrNull() as PipelineStepException

        assertEquals(FailureKind.REPLAY_COMPATIBILITY, failure.failure.kind)
        assertEquals(1, launches)
    }

    @Test
    fun `length-prefixed artifact identity cannot collide across field boundaries`() = runBlocking {
        var launches = 0
        val invoker = shellSpine { launches += 1 }.first
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = ScriptedOperationRuntime { error("sh is registry-routed") },
            registryInvoker = invoker,
        )
        fun entry(source: String, dsl: String) = object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity(source, dsl, "compiler", "runtime", "plugins", "facades")
            override val entryPointId = "entry"
            override suspend fun execute(steps: ScriptedStepFacade) {
                steps.sh(ScriptedCallSiteId("Pipeline.kts:30:effect"), "effect")
            }
        }

        runtime.execute("run-003", entry(source = "a|b", dsl = "c"))
        val failure = runCatching {
            runtime.execute("run-003", entry(source = "a", dsl = "b|c"))
        }.exceptionOrNull() as PipelineStepException

        assertEquals(FailureKind.REPLAY_COMPATIBILITY, failure.failure.kind)
        assertEquals(1, launches)
    }

    @Test
    fun `generated loop scopes keep repeated call sites distinct and replayable`() = runBlocking {
        val (invoker, journal, _) = shellSpine()
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = ScriptedOperationRuntime { error("sh is registry-routed") },
            registryInvoker = invoker,
        )
        val entryPoint = object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity("source", "dsl", "compiler", "runtime", "plugins", "facades")
            override val entryPointId = "loop-entry"

            override suspend fun execute(steps: ScriptedStepFacade) {
                repeat(3) { index ->
                    steps.scoped(ScriptedDynamicScopeId("loop:items[$index]")) {
                        sh(ScriptedCallSiteId("Pipeline.kts:40:effect"), "effect-$index")
                    }
                }
            }
        }

        val runId = "run-004"
        runtime.execute(runId, entryPoint)
        runtime.execute(runId, entryPoint)

        assertEquals(
            listOf("loop:items[0]", "loop:items[1]", "loop:items[2]"),
            journal.scopePathsOf(runId),
        )
    }

    @Test
    fun `nested generated scopes restore the parent path after their block`() = runBlocking {
        val (invoker, journal, _) = shellSpine()
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = ScriptedOperationRuntime { error("sh is registry-routed") },
            registryInvoker = invoker,
        )
        val entryPoint = object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity("source", "dsl", "compiler", "runtime", "plugins", "facades")
            override val entryPointId = "nested-entry"

            override suspend fun execute(steps: ScriptedStepFacade) {
                steps.scoped(ScriptedDynamicScopeId("retry:deploy/attempt:1")) {
                    scoped(ScriptedDynamicScopeId("scope:credentials")) {
                        sh(ScriptedCallSiteId("Pipeline.kts:50:effect"), "nested")
                    }
                }
                steps.sh(ScriptedCallSiteId("Pipeline.kts:50:effect"), "after")
            }
        }

        val runId = "run-005"
        runtime.execute(runId, entryPoint)
        runtime.execute(runId, entryPoint)

        assertEquals(
            listOf("retry:deploy/attempt:1/scope:credentials", ""),
            journal.scopePathsOf(runId),
        )
    }

    @Test
    fun `compiled result dispatches only a typed entry point and rejects other host outcomes without effects`() = runBlocking {
        var launches = 0
        val invoker = shellSpine { launches += 1 }.first
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = ScriptedOperationRuntime { error("sh is registry-routed") },
            registryInvoker = invoker,
        )
        val entryPoint = object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity("source", "dsl", "compiler", "runtime", "plugins", "facades")
            override val entryPointId = "generated-entry"

            override suspend fun execute(steps: ScriptedStepFacade) {
                steps.sh(ScriptedCallSiteId("Pipeline.kts:60:effect"), "effect")
            }
        }
        val cacheKey = CacheKey("cache", CacheKey.V1)

        val executed = runtime.execute(
            runId = "run-006",
            compilation = ScriptCompilationResult.Success(
                output = ScriptEvaluationOutput.CompiledEntryPoint(entryPoint),
                scriptInstance = null,
                diagnostics = emptyList(),
                cacheKey = cacheKey,
            ),
        )
        val rejected = runtime.execute(
            runId = "run-006",
            compilation = ScriptCompilationResult.Success(
                output = ScriptEvaluationOutput.Unit,
                scriptInstance = null,
                diagnostics = emptyList(),
                cacheKey = cacheKey,
            ),
        )
        val failed = runtime.execute(
            runId = "run-006",
            compilation = ScriptCompilationResult.Failure(
                diagnostics = emptyList(),
                cacheKey = cacheKey,
            ),
        )

        assertEquals(ScriptedArtifactExecution.Executed("generated-entry"), executed)
        assertEquals(ScriptedArtifactExecution.Rejected.UnitOutput, rejected)
        assertEquals(ScriptedArtifactExecution.Rejected.CompilationFailed(emptyList()), failed)
        assertEquals(1, launches)
    }

    private fun terminateDurableTestProcesses(controlRoot: java.nio.file.Path) {
        val controlRootText = controlRoot.toString()
        ProcessHandle.allProcesses()
            .filter { handle -> handle.info().commandLine().orElse("").contains(controlRootText) }
            .forEach { handle -> handle.destroyForcibly() }
    }
}
