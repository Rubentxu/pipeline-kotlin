package dev.rubentxu.pipeline.v2.application.scripted

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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class ScriptedScopeTest {
    @Test
    fun `returnStatus exposes a nonzero shell exit as an Int`() = runBlocking {
        val operations = mutableListOf<ScriptedOperation>()
        val runtime = ScriptedRuntime(
            operationRuntime = ScriptedOperationRuntime { operation ->
                operations += operation
                ShellInvocationResult.Status(42)
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
            operationRuntime = ScriptedOperationRuntime { ShellInvocationResult.Stdout("main\n") },
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
                ShellInvocationResult.Failed(PipelineFailure(FailureKind.SCRIPT, "shell exited with code 7"))
            },
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/sh-failure"),
        )

        val failure = runCatching {
            runtime.run(definitionDigest = "test-v1", entryPointId = "main") { sh(script = "exit 7") }
        }.exceptionOrNull()

        assertEquals(ShellExitException::class, failure?.javaClass?.kotlin)
    }

    @Test
    fun `completed scripted status is replayed without reinvoking the effect`() = runBlocking {
        var launches = 0
        val runtime = JournaledScriptedOperationRuntime(
            journal = InMemoryOperationJournal(SystemClock()),
            clock = SystemClock(),
            effectRuntime = ScriptedOperationRuntime {
                launches += 1
                ShellInvocationResult.Status(42)
            },
        )

        suspend fun invokeStatus(): Int = ScriptedRuntime(
            operationRuntime = runtime,
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/replayed-status"),
        ).run(definitionDigest = "test-v1", entryPointId = "main") {
            sh(script = "exit 42", returnStatus = ReturnStatus)
        }

        assertEquals(42, invokeStatus())
        assertEquals(42, invokeStatus())
        assertEquals(1, launches)
    }

    @Test
    fun `changed scripted input fails closed without reinvoking the effect`() = runBlocking {
        var launches = 0
        val runtime = JournaledScriptedOperationRuntime(
            journal = InMemoryOperationJournal(SystemClock()),
            clock = SystemClock(),
            effectRuntime = ScriptedOperationRuntime {
                launches += 1
                ShellInvocationResult.Status(0)
            },
        )

        suspend fun invokeStatus(script: String): Int = ScriptedRuntime(
            operationRuntime = runtime,
            callSites = ScriptedCallSiteProvider.fixed("scripted-test/divergence"),
        ).run(definitionDigest = "test-v1", entryPointId = "main") {
            sh(script = script, returnStatus = ReturnStatus)
        }

        assertEquals(0, invokeStatus("exit 0"))
        val failure = runCatching { invokeStatus("exit 7") }.exceptionOrNull() as PipelineStepException
        assertEquals(FailureKind.REPLAY_COMPATIBILITY, failure.failure.kind)
        assertEquals(1, launches)
    }

    @Test
    fun `running durable shell reattaches without relaunching the effect`() = runBlocking {
        val controlRoot = Files.createTempDirectory("scripted-reattach")
        try {
            val journal = InMemoryOperationJournal(SystemClock())
            val executor = DurableShellExecutor()
            var launches = 0
            val firstRuntime = JournaledScriptedOperationRuntime(
                journal = journal,
                clock = SystemClock(),
                effectRuntime = ScriptedOperationRuntime { operation ->
                    launches += 1
                    val controlDir = controlRoot.resolve(operation.operationId())
                    val process = executor.launch(
                        controlDir = controlDir,
                        scriptContent = "sleep 1; exit 42",
                        opId = operation.operationId(),
                        config = DurableShConfig.fromSystemProperties(),
                        captureStdout = false,
                    )
                    executor.detach(process, controlDir)
                    throw CancellationException("simulated runtime stop after durable launch")
                },
            )

            val first = ScriptedRuntime(firstRuntime, ScriptedCallSiteProvider.fixed("scripted-test/reattach"))
            runCatching {
                first.run(definitionDigest = "test-v1", entryPointId = "main") {
                    sh(script = "sleep 1; exit 42", returnStatus = ReturnStatus)
                }
            }

            val recovered = ScriptedRuntime(
                operationRuntime = JournaledScriptedOperationRuntime(
                    journal = journal,
                    clock = SystemClock(),
                    effectRuntime = ScriptedOperationRuntime { error("must not relaunch a RUNNING operation") },
                    runningReconciler = DurableScriptedOperationReconciler(
                        controlDirRoot = controlRoot,
                        clock = SystemClock(),
                        shell = executor,
                        reattachTimeoutMs = 5_000,
                    ),
                ),
                callSites = ScriptedCallSiteProvider.fixed("scripted-test/reattach"),
            )

            val status = recovered.run(definitionDigest = "test-v1", entryPointId = "main") {
                sh(script = "sleep 1; exit 42", returnStatus = ReturnStatus)
            }

            assertEquals(42, status)
            assertEquals(1, launches)
        } finally {
            terminateDurableTestProcesses(controlRoot)
            controlRoot.toFile().deleteRecursively()
        }
    }

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
        val runtime = JournaledScriptedOperationRuntime(
            journal = InMemoryOperationJournal(SystemClock()),
            clock = SystemClock(),
            effectRuntime = ScriptedOperationRuntime {
                launches += 1
                ShellInvocationResult.Failed(
                    failure = PipelineFailure(FailureKind.INFRASTRUCTURE, "worker disappeared"),
                    durableFailure = provenance,
                )
            },
        )
        val operation = ScriptedOperation(
            definitionDigest = "test-v1",
            entryPointId = "main",
            callSiteId = ScriptedCallSiteId("scripted-test/provenance"),
            dynamicScopePath = emptyList(),
            invocationOrdinal = 0,
            command = dev.rubentxu.pipeline.v2.domain.ShellCommand("exit 1"),
        )

        val initial = runtime.invoke(operation) as ShellInvocationResult.Failed
        val replayed = runtime.invoke(operation) as ShellInvocationResult.Failed

        assertEquals(provenance, initial.durableFailure)
        assertEquals(provenance, replayed.durableFailure)
        assertEquals(1, launches)
    }

    @Test
    fun `compiled entry point replays explicit source call sites without reinvoking effects`() = runBlocking {
        val launches = mutableListOf<String>()
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = JournaledScriptedOperationRuntime(
                journal = InMemoryOperationJournal(SystemClock()),
                clock = SystemClock(),
                effectRuntime = ScriptedOperationRuntime { operation ->
                    launches += operation.callSiteId.value
                    when (operation.command.script) {
                        "branch" -> ShellInvocationResult.Stdout("main\n")
                        "deploy" -> ShellInvocationResult.UnitValue
                        else -> error("unexpected script")
                    }
                },
            ),
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

        assertEquals(listOf("Pipeline.kts:10:branch", "Pipeline.kts:13:deploy"), launches)
    }

    @Test
    fun `compiled artifact mismatch fails closed before relaunching an effect`() = runBlocking {
        var launches = 0
        val runtime = ScriptedArtifactRuntime(
            operationRuntime = JournaledScriptedOperationRuntime(
                journal = InMemoryOperationJournal(SystemClock()),
                clock = SystemClock(),
                effectRuntime = ScriptedOperationRuntime {
                    launches += 1
                    ShellInvocationResult.UnitValue
                },
            ),
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
        val runtime = ScriptedArtifactRuntime(
            JournaledScriptedOperationRuntime(
                journal = InMemoryOperationJournal(SystemClock()),
                clock = SystemClock(),
                effectRuntime = ScriptedOperationRuntime { launches += 1; ShellInvocationResult.UnitValue },
            ),
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
        val launchedScopes = mutableListOf<List<String>>()
        val runtime = ScriptedArtifactRuntime(
            JournaledScriptedOperationRuntime(
                journal = InMemoryOperationJournal(SystemClock()),
                clock = SystemClock(),
                effectRuntime = ScriptedOperationRuntime { operation ->
                    launchedScopes += operation.dynamicScopePath
                    ShellInvocationResult.UnitValue
                },
            ),
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

        runtime.execute("run-004", entryPoint)
        runtime.execute("run-004", entryPoint)

        assertEquals(
            listOf(
                listOf("loop:items[0]"),
                listOf("loop:items[1]"),
                listOf("loop:items[2]"),
            ),
            launchedScopes,
        )
    }

    @Test
    fun `nested generated scopes restore the parent path after their block`() = runBlocking {
        val launchedScopes = mutableListOf<List<String>>()
        val runtime = ScriptedArtifactRuntime(
            JournaledScriptedOperationRuntime(
                journal = InMemoryOperationJournal(SystemClock()),
                clock = SystemClock(),
                effectRuntime = ScriptedOperationRuntime { operation ->
                    launchedScopes += operation.dynamicScopePath
                    ShellInvocationResult.UnitValue
                },
            ),
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

        runtime.execute("run-005", entryPoint)
        runtime.execute("run-005", entryPoint)

        assertEquals(
            listOf(
                listOf("retry:deploy/attempt:1", "scope:credentials"),
                emptyList(),
            ),
            launchedScopes,
        )
    }

    @Test
    fun `compiled result dispatches only a typed entry point and rejects other host outcomes without effects`() = runBlocking {
        var launches = 0
        val runtime = ScriptedArtifactRuntime(
            ScriptedOperationRuntime {
                launches += 1
                ShellInvocationResult.UnitValue
            },
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
