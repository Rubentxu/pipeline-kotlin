package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompiledExecutionPlannerTest {
    private fun step(id: String) = OpaqueStepNode(
        StepId(id), PluginStepId("core.echo"), VersionedStepPayload("dsl-v1", "{\"id\":\"$id\"}")
    )

    private fun pipeline(body: StageBody) = CompiledPipeline(
        id = DefinitionId("pipeline"), source = SourceDescriptor("Jenkinsfile", Digest("source")),
        pluginLockDigest = Digest("lock"), stages = listOf(StageNode(StageId("main"), "main", body = body))
    )

    @Test fun `plans sequential and parallel IR deterministically`() {
        val compiled = CompiledPipeline(
            id = DefinitionId("pipeline"), source = SourceDescriptor("Jenkinsfile", Digest("source")),
            pluginLockDigest = Digest("lock"), stages = listOf(
                StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(step("a")))),
                StageNode(StageId("fanout"), "fanout", body = StageBody.Parallel(listOf(
                    StageNode(StageId("linux"), "linux", body = StageBody.Steps(listOf(step("linux-test")))),
                    StageNode(StageId("mac"), "mac", body = StageBody.Steps(listOf(step("mac-test"))))
                ))),
            )
        )
        val plan = CompiledExecutionPlanner.plan(compiled)
        assertEquals(listOf("a", "linux-test", "mac-test"), plan.linearSteps.map { it.id.value })
        assertEquals(2, plan.units.size)
        assertTrue(plan.units[1] is CompiledExecutionUnit.Concurrent)
    }

    @Test fun `plans nested stage bodies in encounter order`() {
        val nested = pipeline(StageBody.NestedStages(listOf(
            StageNode(StageId("compile"), "compile", body = StageBody.Steps(listOf(step("compile")))),
            StageNode(StageId("test"), "test", body = StageBody.Steps(listOf(step("test")))),
        )))

        assertEquals(listOf("compile", "test"), CompiledExecutionPlanner.plan(nested).linearSteps.map { it.id.value })
    }

    @Test fun `rejects duplicate step identities`() {
        val duplicate = pipeline(StageBody.Steps(listOf(step("same"), step("same"))))
        assertThrows(IllegalArgumentException::class.java) {
            CompiledExecutionPlanner.plan(duplicate)
        }
    }

    @Test fun `rejects multi-step parallel branches until branch semantics are explicit`() {
        val invalid = pipeline(StageBody.Parallel(listOf(
            StageNode(StageId("a"), "a", body = StageBody.Steps(listOf(step("a1"), step("a2")))),
            StageNode(StageId("b"), "b", body = StageBody.Steps(listOf(step("b1"))))
        )))
        assertThrows(IllegalArgumentException::class.java) {
            CompiledExecutionPlanner.plan(invalid)
        }
    }

    @Test fun `rejects unimplemented matrix bodies and non-step parallel branches`() {
        val matrix = pipeline(StageBody.Matrix(MatrixSpec(mapOf("os" to listOf("linux")))))
        assertThrows(IllegalStateException::class.java) {
            CompiledExecutionPlanner.plan(matrix)
        }

        val nestedBranch = pipeline(StageBody.Parallel(listOf(
            StageNode(StageId("nested"), "nested", body = StageBody.NestedStages(listOf(
                StageNode(StageId("inner"), "inner", body = StageBody.Steps(listOf(step("inner")))),
            ))),
            StageNode(StageId("other"), "other", body = StageBody.Steps(listOf(step("other")))),
        )))
        assertThrows(IllegalStateException::class.java) {
            CompiledExecutionPlanner.plan(nestedBranch)
        }
    }
}
