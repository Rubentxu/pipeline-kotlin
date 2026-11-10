package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch
import dev.rubentxu.pipeline.v2.runtime.RunConfiguration
import dev.rubentxu.pipeline.v2.runtime.RunConfigurationOutcome
import dev.rubentxu.pipeline.v2.runtime.RunPolicy
import dev.rubentxu.pipeline.v2.runtime.RuntimeWorkspaceTransport
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/**
 * Where the configuration of a run sits in the composition of the environment.
 *
 * ## The guarantee under trial
 *
 * That the configuration is the LOWEST layer of the environment: the
 * `environment { }` of a stage is composed on top of it and the `withEnv`
 * scope on top of that, and the more specific scope wins. A configuration
 * that could override the pipeline would be a configuration that decides how
 * the run is EXECUTED, and this channel is about what the steps SEE.
 *
 * ## Why the rows are about the COMPOSITION and not about the rules
 *
 * Because the rules have their own instrument (the test of the
 * configuration itself). What these rows observe is the ORDER: three
 * layers, one rule, and the rule is the one the product already had.
 *
 * ## What this does not freeze
 *
 * The launcher, the sandbox and the process. This is a pure composition of
 * pure functions, and the integration lane observes what a step really
 * sees.
 */
class RunConfigurationPrecedenceTest {

    @Test
    fun `the configuration reaches the BASE environment of the run`() {
        val options = canonicalShellOptions(policy(configuration = mapOf("BUILD_PROFILE" to "release")))

        assertEquals(
            "release",
            options.env["BUILD_PROFILE"]?.materialize(),
            "the configuration is the base layer of the environment",
        )
    }

    @Test
    fun `a run with no configuration has the base environment it always had`() {
        val options = canonicalShellOptions(policy())

        assertTrue(
            options.env.isEmpty(),
            "a run that was given nothing projects nothing into the environment of its steps",
        )
    }

    @Test
    fun `the environment of the stage wins over the configuration`() {
        val base = canonicalShellOptions(policy(configuration = mapOf("BUILD_PROFILE" to "release")))
        val stage = stageWith(environment = mapOf("BUILD_PROFILE" to "debug"))

        val options = stage.projectShellOptions(base)

        assertEquals(
            "debug",
            options.env["BUILD_PROFILE"]?.materialize(),
            "the stage declares the environment of its steps and wins over the configuration",
        )
    }

    @Test
    fun `the configuration survives when the stage does not name it`() {
        val base = canonicalShellOptions(policy(configuration = mapOf("BUILD_PROFILE" to "release")))
        val stage = stageWith(environment = mapOf("OTHER" to "value"))

        val options = stage.projectShellOptions(base)

        assertEquals(
            "release",
            options.env["BUILD_PROFILE"]?.materialize(),
            "the configuration reaches the steps the stage did not speak for",
        )
    }

    @Test
    fun `the withEnv scope wins over the stage`() {
        val base = canonicalShellOptions(policy(configuration = mapOf("BUILD_PROFILE" to "release")))
        val stage = stageWith(environment = mapOf("BUILD_PROFILE" to "debug"))
        val staged = stage.projectShellOptions(base)

        val projected = projectInvocationPatch(
            ExecutionContext.EMPTY,
            staged,
            ExecutionContextPatch.Environment(mapOf("BUILD_PROFILE" to "local")),
        )

        val options = (projected as InvocationPatchProjection.Applied).shOptions
        assertEquals(
            "local",
            options.env["BUILD_PROFILE"]?.materialize(),
            "the withEnv scope is the most specific one and wins over the stage",
        )
    }

    private fun stageWith(environment: Map<String, String>): StageNode = StageNode(
        id = StageId("stage-1"),
        name = "stage",
        environment = EnvironmentSpec(environment),
        body = StageBody.Steps(emptyList()),
    )

    private fun policy(configuration: Map<String, String> = emptyMap()): RunPolicy {
        val accepted = RunConfiguration.of(configuration)
        val values = (accepted as RunConfigurationOutcome.Accepted).configuration
        return RunPolicy(
            clock = FixedClock,
            idGenerator = SequentialIdGenerator(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            controlDirRoot = CONTROL_ROOT,
            sandboxProfile = SandboxProfile.NONE,
            workspace = RuntimeWorkspaceTransport(base = null, ownership = WorkspaceOwnership.PIPELINEK),
            credentialProvider = null,
            withCredentialsExecutor = null,
            allowNetwork = false,
            secretPatternRegistry = SecretPatternRegistry(),
            configuration = values,
        )
    }

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        val CONTROL_ROOT: Path = Path.of("/nonexistent-control-root")
    }
}
