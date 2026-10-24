package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Whether a pipeline declares a credential binding.
 *
 * ## The guarantee
 * A run that asks for no secret never opens the secret store, so it is never
 * asked for the passphrase that store is behind. The store's presence on disk
 * is a fact about the machine; the need for it is a fact about the run, and
 * asking the machine made every run on a machine that had ever stored a secret
 * unlock the place where secrets live — including the first pipeline a new
 * operator writes, and including every pipeline that binds nothing.
 *
 * ## The failures this covers
 * Two shapes, measured against the product rather than imagined.
 *
 * The first is the empty store: a pipeline that binds nothing met a refusal
 * that named the passphrase, on a machine whose store held a secret nobody in
 * that run had asked for.
 *
 * The second is a binding hidden inside a body. A `withCredentials` written
 * inside `withEnv`, inside `retry`, inside a branch of `parallel` is still a
 * binding of the run, and a walk that stopped at the top level would answer
 * "no" to a pipeline that has one — and then the run would look for a secret it
 * had just refused to unlock.
 *
 * ## Why the instrument is this cheap
 * The question is asked of a declaration the author wrote, so it is a pure call
 * over plain data: no store, no disk, no clock, no passphrase.
 */
class CredentialBindingDeclarationTest {

    private fun pipelineOf(vararg steps: StepSpec): PipelineSpec =
        PipelineSpec(stages = listOf(StageSpec(name = "stage", steps = steps.toList())))

    private fun binding(id: String = "mi-secreto"): StepSpec.WithCredentialsBlock =
        StepSpec.WithCredentialsBlock(
            credentialsId = CredentialsId(id),
            purpose = "a secret the step reads",
            bindings = listOf(
                StepSpec.CredentialsBinding.string(id, "API_KEY"),
            ),
            steps = emptyList(),
        )

    @Test
    @DisplayName("a pipeline that names no secret declares no binding")
    fun `a pipeline that names no secret declares no binding`() {
        assertFalse(
            pipelineOf(StepSpec.Echo("hola")).declaresCredentialBinding(),
            "a pipeline that only says something asks for nothing",
        )
        assertFalse(
            PipelineSpec(stages = emptyList()).declaresCredentialBinding(),
            "a pipeline with no stages asks for nothing",
        )
    }

    @Test
    @DisplayName("a binding is a binding")
    fun `a binding is a binding`() {
        assertTrue(
            pipelineOf(binding()).declaresCredentialBinding(),
            "a step that asks for a secret asks for a secret",
        )
    }

    @Test
    @DisplayName("a checkout that names a secret is a binding too")
    fun `a checkout that names a secret is a binding`() {
        assertTrue(
            pipelineOf(
                StepSpec.Checkout(
                    scm = GitScm(url = "file:///tmp/repo", credentialsId = CredentialsId("git")),
                ),
            ).declaresCredentialBinding(),
            "a checkout of a private repository asks for a secret",
        )
        assertFalse(
            pipelineOf(
                StepSpec.Checkout(scm = GitScm(url = "file:///tmp/repo")),
            ).declaresCredentialBinding(),
            "a checkout of a public repository asks for nothing",
        )
    }

    @Test
    @DisplayName("a binding inside a body is a binding of the run")
    fun `a binding inside a body is a binding of the run`() {
        assertTrue(
            pipelineOf(StepSpec.WithEnv(overrides = listOf("A=B"), steps = listOf(binding())))
                .declaresCredentialBinding(),
            "a binding written inside an environment block is still a binding",
        )
        assertTrue(
            pipelineOf(StepSpec.RetryBlock(count = 3, steps = listOf(binding())))
                .declaresCredentialBinding(),
            "a binding written inside a retry is still a binding",
        )
        assertTrue(
            pipelineOf(StepSpec.TimeoutBlock(time = 1, unit = "s", steps = listOf(binding())))
                .declaresCredentialBinding(),
            "a binding written inside a deadline is still a binding",
        )
        assertTrue(
            pipelineOf(
                StepSpec.Parallel(
                    branches = listOf(
                        StepSpec.BranchSpec(name = "a", steps = listOf(binding())),
                    ),
                ),
            ).declaresCredentialBinding(),
            "a binding written in a branch is still a binding",
        )
        assertTrue(
            pipelineOf(StepSpec.Slot(slotName = "s", step = binding()))
                .declaresCredentialBinding(),
            "a binding written in a slot is still a binding",
        )
    }

    @Test
    @DisplayName("a body with nothing inside asks for nothing")
    fun `a body with nothing inside asks for nothing`() {
        assertFalse(
            pipelineOf(StepSpec.WithEnv(overrides = listOf("A=B"), steps = listOf(StepSpec.Echo("hola"))))
                .declaresCredentialBinding(),
            "a body that names no secret asks for nothing",
        )
        assertFalse(
            pipelineOf(
                StepSpec.Parallel(
                    branches = listOf(
                        StepSpec.BranchSpec(name = "a", steps = listOf(StepSpec.Echo("hola"))),
                    ),
                ),
            ).declaresCredentialBinding(),
            "a branch that names no secret asks for nothing",
        )
    }
}
