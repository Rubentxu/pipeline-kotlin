package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.step.http.HttpHeader
import dev.rubentxu.pipeline.v2.domain.step.http.HttpMethod
import dev.rubentxu.pipeline.v2.domain.step.http.StatusRange
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DslCompiledPipelineCompilerTest {

    private val source = """
        pipeline {
            stages {
                stage("Build") {
                    environment { env("CI", "true") }
                    options { timeout(30) }
                    echo("hello")
                    sh("./gradlew test")
                }
            }
        }
    """.trimIndent()

    private fun fixture() = pipeline {
        stages {
            stage("Build") {
                environment { env("CI", "true") }
                options { timeout(30) }
                echo("hello")
                sh("./gradlew test")
            }
        }
    }

    @Test
    fun `DSL fixture compiles directly to canonical IR`() {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = fixture(),
            sourcePath = "build.pipeline.kts",
            sourceContent = source,
            pluginLockDigest = Digest("lock-v1"),
        )

        assertEquals("build.pipeline.kts", compiled.source.path)
        assertEquals(mapOf("CI" to "true"), compiled.stages.single().environment.values)
        assertEquals(listOf("timeout=30"), compiled.stages.single().options.map { "${it.name}=${it.value}" })

        val body = compiled.stages.single().body as StageBody.Steps
        assertEquals(listOf("build/echo-0", "build/sh-0"), body.steps.map { it.id.value })
        assertEquals(listOf("core.echo", "core.sh"), body.steps.map { it.pluginStepId.value })
        assertTrue(body.steps.all { it.payload.schemaVersion == "dsl-v1" })
        assertTrue(body.steps.first().payload.encoded.contains("hello"))
        assertTrue(body.steps[1].payload.encoded.contains("./gradlew test"))
    }

    @Test
    fun `stage-level agent is rejected instead of compiling to unread metadata`() {
        // S0 Semantic Honesty Gate: agent(label) at stage level used to compile
        // into StageNode.agent while NO runtime component ever read it (no
        // distributor, no scheduler; the agent label never reached any event).
        // Metadata without an interpreter is a silent lie, so the DSL now
        // refuses the call instead of storing it.
        val ex = assertThrows<IllegalArgumentException> {
            pipeline {
                stages {
                    stage("Build") {
                        agent("linux")
                        echo("hello")
                    }
                }
            }
        }
        assertTrue(
            (ex.message ?: "").contains("agent"),
            "diagnostic must name the unsupported construct, got: ${ex.message}",
        )
    }

    @Test
    fun `same source and lock produce the same definition and source identity`() {
        val first = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v1"),
        )
        val second = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v1"),
        )

        assertEquals(first, second)
        assertTrue(first.id.value.isNotBlank())
    }

    @Test
    fun `changing the plugin lock changes the definition identity`() {
        val first = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v1"),
        )
        val second = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v2"),
        )

        assertTrue(first.id != second.id)
    }

    @Test
    fun `adding a different step does not renumber existing step identities`() {
        val baseline = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v1"),
        )
        val changed = DslCompiledPipelineCompiler.compile(
            pipeline {
                stages {
                    stage("Build") {
                        echo("hello")
                        error("diagnostic")
                        sh("./gradlew test")
                    }
                }
            },
            "build.pipeline.kts",
            source,
            Digest("lock-v1"),
        )

        val baselineIds = (baseline.stages.single().body as StageBody.Steps).steps.map { it.id.value }
        val changedIds = (changed.stages.single().body as StageBody.Steps).steps.map { it.id.value }
        assertEquals(listOf("build/echo-0", "build/sh-0"), baselineIds)
        assertEquals(listOf("build/echo-0", "build/error-0", "build/sh-0"), changedIds)
    }

    @Test
    fun `writeFile step compiles to core file writeFile with typed payload`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    writeFile("out.txt", "hello world", "utf-8")
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "writeFile pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val step = body.steps.single() as OpaqueStepNode
        assertEquals("core.file.writeFile", step.pluginStepId.value)
        assertTrue(step.payload.encoded.contains("\"kind\":\"writeFile\""))
        assertTrue(step.payload.encoded.contains("\"file\":\"out.txt\""))
        assertTrue(step.payload.encoded.contains("\"text\":\"hello world\""))
        assertTrue(step.payload.encoded.contains("\"encoding\":\"utf-8\""))
    }

    @Test
    fun `catchError step compiles to emit-event enter marker + inner steps + shell + trigger marker`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    catchError(buildResult = "FAILURE", stageResult = "FAILURE") {
                        sh("exit 1")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "catchError pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val ids = body.steps.map { it.id.value }
        val plugins = body.steps.map { it.pluginStepId.value }

        // Must include the entry marker and trigger marker for CatchErrorTriggered
        assertTrue(ids.any { it.contains("catch-error-enter-") }, "Should have catch-error-enter marker")
        assertTrue(ids.any { it.contains("catch-error-trigger-") }, "Should have catch-error-trigger marker")
        assertTrue(plugins.any { it == "core.emit.event" }, "Should have emit.event markers")
        assertTrue(plugins.any { it == "core.sh" }, "Should have core.sh wrapper")
    }

    @Test
    fun `unstable step compiles to StageMarkedUnstable event + exit-0 shell`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    unstable("something went wrong")
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "unstable pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val ids = body.steps.map { it.id.value }
        val plugins = body.steps.map { it.pluginStepId.value }

        assertEquals(2, body.steps.size, "unstable should produce exactly 2 nodes")
        assertTrue(ids.any { it.contains("unstable-") }, "Should have unstable marker node")
        assertTrue(ids.any { it.contains("unstable-exit-0-") }, "Should have exit-0 shell node")
        assertTrue(plugins.any { it == "core.emit.event" }, "Should emit StageMarkedUnstable event")
        assertTrue(plugins.any { it == "core.sh" }, "Should emit exit-0 shell")
    }

    @Test
    fun `warnError compiles to typed UNSTABLE catchError metadata and StageMarkedUnstable`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    warnError("deprecation warning") {
                        sh("exit 1")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "warnError pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val plugins = body.steps.map { it.pluginStepId.value }

        assertTrue(plugins.any { it == "core.emit.event" }, "Should have emit.event markers")
        assertTrue(plugins.any { it == "core.sh" }, "Should have core.sh wrapper")

        // warnError is catchError(UNSTABLE) and must visibly mark the stage unstable.
        val emitSteps = body.steps.filter { it.pluginStepId.value == "core.emit.event" }
        assertTrue(emitSteps.any { it.payload.encoded.contains("\"stageResult\":\"UNSTABLE\"") },
            "warnError should preserve an unquoted UNSTABLE stage result")
        assertTrue(emitSteps.any { it.payload.encoded.contains("\"kind\":\"StageMarkedUnstable\"") },
            "warnError should emit StageMarkedUnstable after closing catchError scope")
    }

    @Test
    fun `catchError inlines inner steps into single shell wrapper`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    catchError {
                        echo("inner")
                        sh("echo hello")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "catchError pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps

        // LFC1-007 remediation: catchError must emit exactly 3 nodes (enter marker,
        // single shell wrapper, trigger marker). The previous implementation emitted
        // inner steps as separate canonical nodes AND a shell wrapper containing the
        // same steps, double-running the inner block. That broke catchError semantics
        // (FIND-DV-DUPL-01).
        assertEquals(3, body.steps.size, "catchError must produce exactly 3 nodes")
        val plugins = body.steps.map { it.pluginStepId.value }
        assertEquals(listOf("core.emit.event", "core.sh", "core.emit.event"), plugins,
            "catchError must emit exactly: enter-marker + shell-wrapper + trigger-marker")

        // The inner step commands must be inlined into the shell wrapper's script body
        val shellNode = body.steps.single { it.pluginStepId.value == "core.sh" }
        val shellPayload = shellNode.payload.encoded
        assertTrue(shellPayload.contains("echo hello"),
            "Shell wrapper must contain inner sh() command. Payload: $shellPayload")
    }

    @Test
    fun `catchError lifts inner unstable after its trigger while retaining the shell wrapper`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    catchError(buildResult = "FAILURE") {
                        unstable("inner unstable")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "catchError unstable pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val plugins = body.steps.map { it.pluginStepId.value }
        val triggerIndex = body.steps.indexOfFirst {
            it.id.value.contains("catch-error-trigger-")
        }
        val unstableIndex = body.steps.indexOfFirst {
            it.payload.encoded.contains("\"kind\":\"StageMarkedUnstable\"")
        }

        assertEquals(
            listOf("core.emit.event", "core.sh", "core.emit.event", "core.emit.event", "core.sh"),
            plugins,
        )
        assertTrue(triggerIndex >= 0, "catchError must retain its trigger marker")
        assertTrue(unstableIndex > triggerIndex, "inner unstable must follow CatchErrorTriggered")
        assertTrue(
            (body.steps[1] as OpaqueStepNode).payload.encoded.contains("set +e"),
            "catchError must retain an empty shell wrapper for its heredoc",
        )
    }

    @Test
    fun `nested catchError inside warnError compiles inside the parent scope without shell comments`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    warnError("outer section") {
                        catchError {
                            sh("exit 1")
                        }
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "nested-catch.pipeline.kts",
            "nested catchError pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val plugins = body.steps.map { it.pluginStepId.value }

        // parent enter, nested enter, nested shell, nested trigger, parent trigger, StageMarkedUnstable
        assertEquals(
            listOf(
                "core.emit.event",
                "core.emit.event",
                "core.sh",
                "core.emit.event",
                "core.emit.event",
                "core.emit.event",
            ),
            plugins,
            "Nested catch groups must be rewritten inside the parent scope, not compiled into its heredoc",
        )
        body.steps.forEach { step ->
            assertFalse(
                step.payload.encoded.contains("legacy step"),
                "No workflow-control child may compile into a shell comment: ${step.payload.encoded}",
            )
        }
        val shells = body.steps.filter { it.pluginStepId.value == "core.sh" }
        assertEquals(1, shells.size, "Only the nested catch body shell may exist")
        assertTrue(shells.single().payload.encoded.contains("exit 1"))

        // The nested scope must sit between the parent enter and trigger markers so it
        // inherits the parent overlay while it is active.
        val parentEnter = body.steps.indexOfFirst { it.id.value.contains("warn-error-enter-") }
        val nestedEnter = body.steps.indexOfFirst { it.id.value.contains("catch-error-enter-") }
        val nestedTrigger = body.steps.indexOfFirst { it.id.value.contains("catch-error-trigger-") }
        val parentTrigger = body.steps.indexOfFirst { it.id.value.contains("warn-error-trigger-") }
        assertTrue(parentEnter in 0..nestedEnter, "Parent enter must precede the nested scope")
        assertTrue(nestedEnter in 0..nestedTrigger, "Nested enter must precede its trigger")
        assertTrue(nestedTrigger < parentTrigger, "Nested scope must close before the parent scope closes")
    }

    @Test
    fun `catchError with a non-embeddable inner step fails compilation instead of a shell comment`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    catchError {
                        sleep(2)
                    }
                }
            }
        }
        val error = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            DslCompiledPipelineCompiler.compile(
                spec,
                "loud-fallback.pipeline.kts",
                "loud fallback pipeline",
                Digest("lock-v1"),
            )
        }
        assertTrue(
            error.message!!.contains("sleep"),
            "The typed compile error must name the offending step kind: ${error.message}",
        )
        assertFalse(
            error.message!!.contains("legacy step"),
            "The compiler must never fall back to a silent shell comment: ${error.message}",
        )
    }

    @Test
    fun `milestone step compiles to canonical core milestone payload with ordinal and label`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    milestone(ordinal = 1, label = "post-error")
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "milestone.pipeline.kts",
            "milestone pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val step = body.steps.single() as OpaqueStepNode
        assertEquals("core.milestone", step.pluginStepId.value)
        assertTrue(step.payload.encoded.contains("\"kind\":\"milestone\""))
        assertTrue(step.payload.encoded.contains("\"ordinal\":1"))
        assertTrue(step.payload.encoded.contains("\"label\":\"post-error\""))
    }

    // ── WU-091 G3: core.lock DSL surface lowers through the single wire authority ──

    @Test
    fun `lock step compiles to core lock block node whose payload is the wire codec encoding`() {
        val spec = pipeline {
            stages {
                stage("Deploy") {
                    lock("staging") {
                        sh("./deploy.sh")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "lock.pipeline.kts",
            "lock pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val block = body.steps.single() as BlockStepNode
        assertEquals("core.lock", block.pluginStepId.value)
        // G3.4: the compiler did not hand-write the payload — it is byte-identical
        // to what the wire authority itself produces for the same typed input.
        assertEquals(
            CoreLockWireCodec.encode(CoreLockInput(resource = "staging")).value,
            block.payload.encoded,
        )
        // G3.3 side effect of the same law: the payload is the CODEC spelling,
        // never the generic-else fallback `{"kind":"lock"}`.
        assertFalse(
            block.payload.encoded.contains("\"kind\""),
            "lock payload must come from CoreLockWireCodec, not the generic else payload",
        )
        // The body children survive lowering (B11 law for the lock family member).
        val childIds = block.body.map { it.id.value }
        assertTrue(
            childIds.any { it.endsWith("/sh-0") },
            "lock body must contain the sh child; got $childIds",
        )
        assertTrue(
            (block.body.single() as OpaqueStepNode).payload.encoded.contains("./deploy.sh"),
        )
    }

    @Test
    fun `lock options reach the wire verbatim without the compiler resolving them`() {
        val spec = pipeline {
            stages {
                stage("Deploy") {
                    lock(
                        resource = "db-migrate",
                        timeoutSeconds = 120,
                        reason = "serial schema migration",
                        skipIfLocked = false,
                    ) {
                        echo("migrating")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "lock-options.pipeline.kts",
            "lock options pipeline",
            Digest("lock-v1"),
        )
        val block = (compiled.stages.single().body as StageBody.Steps).steps.single() as BlockStepNode
        assertEquals(
            CoreLockWireCodec.encode(
                CoreLockInput(
                    resource = "db-migrate",
                    timeoutSeconds = 120,
                    reason = "serial schema migration",
                ),
            ).value,
            block.payload.encoded,
        )
    }

    @Test
    fun `contradictory lock declaration is encoded verbatim and left to the typed decision`() {
        // skipIfLocked + timeout is individually valid and jointly contradictory.
        // G3 law: the COMPILER must not resolve the contradiction (that decision
        // belongs to lockIntentOf at the Step, once); it encodes both fields.
        val spec = pipeline {
            stages {
                stage("Deploy") {
                    lock(
                        resource = "db",
                        timeoutSeconds = 30,
                        skipIfLocked = true,
                    ) {
                        echo("never runs in this shape")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "lock-contradiction.pipeline.kts",
            "lock contradiction pipeline",
            Digest("lock-v1"),
        )
        val block = (compiled.stages.single().body as StageBody.Steps).steps.single() as BlockStepNode
        assertEquals(
            CoreLockWireCodec.encode(
                CoreLockInput(resource = "db", timeoutSeconds = 30, skipIfLocked = true),
            ).value,
            block.payload.encoded,
        )
    }

    // ── WU-092 G3: core.input DSL surface lowers through the single wire authority ──

    @Test
    fun `input step compiles to core input block whose payload is the wire codec encoding`() {
        val spec = pipeline {
            stages {
                stage("Deploy") {
                    input("Deploy to production?") {
                        sh("./deploy.sh")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "input.pipeline.kts",
            "input pipeline",
            Digest("input-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val block = body.steps.single() as BlockStepNode
        assertEquals("core.input", block.pluginStepId.value)
        // G3.4: the payload is byte-identical to what the wire authority itself
        // produces for the same typed input.
        assertEquals(
            CoreInputWireCodec.encode(CoreInputInput(message = "Deploy to production?")).value,
            block.payload.encoded,
        )
        assertFalse(
            block.payload.encoded.contains("\"kind\""),
            "input payload must come from CoreInputWireCodec, not the generic else payload",
        )
        // The body children survive lowering.
        assertTrue(
            block.body.any { it.id.value.endsWith("/sh-0") },
            "input body must contain the sh child; got ${block.body.map { it.id.value }}",
        )
    }

    @Test
    fun `input options reach the wire verbatim without the compiler deciding anything`() {
        // G3 law: the compiler encodes; it does NOT resolve a blank message. That
        // rule belongs to inputIntentOf, applied once at the Step.
        val spec = pipeline {
            stages {
                stage("Release") {
                    input(
                        message = "Promote 1.2.3?",
                        ok = "Promote",
                        submitter = "release-team",
                        id = "promote-123",
                        timeoutSeconds = 300,
                    ) {
                        echo("promoting")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "input-options.pipeline.kts",
            "input options pipeline",
            Digest("input-v1"),
        )
        val block = (compiled.stages.single().body as StageBody.Steps).steps.single() as BlockStepNode
        assertEquals(
            CoreInputWireCodec.encode(
                CoreInputInput(
                    message = "Promote 1.2.3?",
                    ok = "Promote",
                    submitter = "release-team",
                    id = "promote-123",
                    timeoutSeconds = 300,
                ),
            ).value,
            block.payload.encoded,
        )
    }

    @Test
    fun `a blank input message still compiles and is rejected by the step not the compiler`() {
        val spec = pipeline {
            stages {
                stage("Broken") {
                    input(message = "   ") {
                        echo("must not run")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "input-blank.pipeline.kts",
            "input blank pipeline",
            Digest("input-v1"),
        )
        val block = (compiled.stages.single().body as StageBody.Steps).steps.single() as BlockStepNode
        // The compiler is a transcription layer: it does not decide what a valid
        // question is, so the contradiction reaches the Step and is decided ONCE
        // there as a typed rejection.
        assertEquals("core.input", block.pluginStepId.value)
        assertTrue(
            block.payload.encoded.contains("\"message\":\"   \""),
            "the blank question must be encoded verbatim, got ${block.payload.encoded}",
        )
    }

    @Test
    fun `lock inside a parallel branch lowers with its body children`() {
        // G4 scenario seed (sibling contention): the branch-scope lock builder
        // must produce the same canonical block shape as the stage-scope one.
        val spec = pipeline {
            stages {
                stage("Matrix") {
                    parallel {
                        branch("left") {
                            lock("shared") {
                                echo("left critical section")
                            }
                        }
                        branch("right") {
                            echo("right free")
                        }
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "lock-parallel.pipeline.kts",
            "lock parallel pipeline",
            Digest("lock-v1"),
        )
        val stageBody = compiled.stages.single().body
        val parallelBody = stageBody as StageBody.Parallel
        val leftSteps = (parallelBody.branches.single { it.name == "left" }.body as StageBody.Steps).steps
        val rightSteps = (parallelBody.branches.single { it.name == "right" }.body as StageBody.Steps).steps
        val leftLock = leftSteps.single() as BlockStepNode
        assertEquals("core.lock", leftLock.pluginStepId.value)
        assertEquals(
            CoreLockWireCodec.encode(CoreLockInput(resource = "shared")).value,
            leftLock.payload.encoded,
        )
        assertTrue(
            leftLock.body.any { it.id.value.endsWith("/echo-0") },
            "branch lock body must contain the echo child; got ${leftLock.body.map { it.id.value }}",
        )
        assertEquals("core.echo", rightSteps.single().pluginStepId.value)
    }

    // ── WU-093 G3: core.httpRequest DSL surface lowers through the single wire authority ──

    @Test
    fun `httpRequest compiles to a core http ATOMIC node whose payload is the wire codec encoding`() {
        val spec = pipeline {
            stages {
                stage("Notify") {
                    httpRequest("https://example.test/hook")
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "http.pipeline.kts",
            "http pipeline",
            Digest("http-v1"),
        )
        val node = (compiled.stages.single().body as StageBody.Steps).steps.single()
        // An atomic Step: a request has no enclosed body, so this must NOT be a
        // BlockStepNode. Getting this wrong would hand the runtime a block shape
        // for a step that owns no body.
        assertTrue(
            node is OpaqueStepNode,
            "httpRequest must lower to an atomic node; got ${node::class.simpleName}",
        )
        assertEquals("core.httpRequest", node.pluginStepId.value)
        // G3.4: byte-identical to what the wire authority produces for the same
        // typed input. The compiler knows the transformation, never the format.
        assertEquals(
            CoreHttpWireCodec.encode(CoreHttpInput(url = "https://example.test/hook")).value,
            node.payload.encoded,
        )
    }

    @Test
    fun `http options reach the wire verbatim without the compiler deciding anything`() {
        // G3 law: the compiler encodes; it does NOT resolve a blank url, a body
        // sent with a method that cannot carry one, or a negative timeout. Those
        // rules belong to httpIntentOf, applied once at the Step.
        val spec = pipeline {
            stages {
                stage("Publish") {
                    httpRequest(
                        url = "https://example.test/releases",
                        method = HttpMethod.Post,
                        customHeaders = listOf(
                            HttpHeader.of("X-Token", "abc123"),
                            HttpHeader.of("Set-Cookie", "a=1"),
                            HttpHeader.of("Set-Cookie", "b=2"),
                        ),
                        body = """{"name":"1.2.3"}""",
                        contentType = "APPLICATION_JSON",
                        acceptType = "text/plain",
                        validResponseCodes = listOf(StatusRange.Single(201), StatusRange.Span(400, 404)),
                        timeoutSeconds = 0,
                        authentication = CredentialsId("release-bot"),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "http-options.pipeline.kts",
            "http options pipeline",
            Digest("http-v1"),
        )
        val node = (compiled.stages.single().body as StageBody.Steps).steps.single()
        assertEquals(
            CoreHttpWireCodec.encode(
                CoreHttpInput(
                    url = "https://example.test/releases",
                    method = HttpMethod.Post,
                    customHeaders = listOf(
                        HttpHeader.of("X-Token", "abc123"),
                        HttpHeader.of("Set-Cookie", "a=1"),
                        HttpHeader.of("Set-Cookie", "b=2"),
                    ),
                    body = """{"name":"1.2.3"}""",
                    contentType = "APPLICATION_JSON",
                    acceptType = "text/plain",
                    validResponseCodes = listOf(StatusRange.Single(201), StatusRange.Span(400, 404)),
                    timeoutSeconds = 0,
                    authentication = CredentialsId("release-bot"),
                ),
            ).value,
            node.payload.encoded,
        )
    }

    @Test
    fun `a repeated header survives lowering as a list and not a collapsed object`() {
        // The mutation this guards is quiet: a JSON object keyed by header name
        // would keep the LAST Set-Cookie and drop the first, and the pipeline would
        // still compile, still encode, and still be green.
        val spec = pipeline {
            stages {
                stage("Cookies") {
                    httpRequest(
                        url = "https://example.test/login",
                        method = HttpMethod.Post,
                        customHeaders = listOf(
                            HttpHeader.of("Set-Cookie", "session=one"),
                            HttpHeader.of("Set-Cookie", "csrf=two"),
                        ),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "http-headers.pipeline.kts",
            "http headers pipeline",
            Digest("http-v1"),
        )
        val node = (compiled.stages.single().body as StageBody.Steps).steps.single()
        assertEquals(
            2,
            Regex("Set-Cookie").findAll(node.payload.encoded).count(),
            "both Set-Cookie headers must reach the wire; got ${node.payload.encoded}",
        )
    }

    @Test
    fun `a declaration the compiler must not judge still compiles`() {
        // A body on a GET is a Step-level rejection, not a compile error. If the
        // compiler ever starts resolving this, the rejection would fire at build
        // time for a rule that belongs to the runtime, and a pipeline could not
        // declare-and-observe it as a typed failure.
        val spec = pipeline {
            stages {
                stage("Questionable") {
                    httpRequest(url = "https://example.test/x", method = HttpMethod.Get, body = "oops")
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "http-declaration.pipeline.kts",
            "http declaration pipeline",
            Digest("http-v1"),
        )
        val node = (compiled.stages.single().body as StageBody.Steps).steps.single()
        assertEquals("core.httpRequest", node.pluginStepId.value)
    }
}
