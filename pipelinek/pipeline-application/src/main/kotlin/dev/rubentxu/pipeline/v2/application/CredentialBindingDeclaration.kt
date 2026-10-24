package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Whether a pipeline declares a credential binding.
 *
 * ## The guarantee
 * A run that asks for no secret never opens the secret store. The store has a
 * passphrase in front of it, and a pipeline that binds no credential has no use
 * for either: asking for a secret from a run that never wanted one puts a door
 * in front of the wrong room, and the operator meets it before their first
 * pipeline has run a single step.
 *
 * ## Why the question is asked of the DECLARATION and not of the machine
 * The store's presence on disk is a fact about the MACHINE. The need for it is a
 * fact about the RUN. A machine that had ever stored a secret made every run
 * ask for its passphrase — including the runs that bind nothing, and including
 * the first pipeline a new operator writes on a machine where the store happens
 * to exist.
 *
 * ## Why the walk is exhaustive
 * The step hierarchy is sealed, so the compiler holds this `when` to account: a
 * new kind of step does not compile until somebody says whether it can bind a
 * credential. A step that carries a body is walked, because a binding declared
 * inside a body is still a binding of the run.
 */
internal fun PipelineSpec.declaresCredentialBinding(): Boolean =
    stages.any { stage -> stage.steps.any { step -> step.declaresCredentialBinding() } }

private fun StepSpec.declaresCredentialBinding(): Boolean = when (this) {
    is StepSpec.WithCredentialsBlock -> true
    is StepSpec.Checkout -> scm.declaresCredentialBinding()
    is StepSpec.RegistryBlockSpec -> body.any { it.declaresCredentialBinding() }
    is StepSpec.Parallel -> branches.any { branch ->
        branch.steps.any { it.declaresCredentialBinding() }
    }
    is StepSpec.Slot -> step.declaresCredentialBinding()
    is StepSpec.CatchError -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.WarnError -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.WithEnv -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.Dir -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.Timestamps -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.WaitUntilBlock -> body.any { it.declaresCredentialBinding() }
    is StepSpec.TimeoutBlock -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.RetryBlock -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.Lock -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.Input -> steps.any { it.declaresCredentialBinding() }
    is StepSpec.NodeNoOp -> steps.any { it.declaresCredentialBinding() }
    // The leaves: a step that names no secret.
    is StepSpec.RegistryStepSpec,
    is StepSpec.Echo,
    is StepSpec.Shell,
    is StepSpec.Error,
    is StepSpec.Sleep,
    is StepSpec.WriteFile,
    is StepSpec.ReadFile,
    is StepSpec.FileExists,
    is StepSpec.ArchiveArtifacts,
    is StepSpec.ArtifactQuery,
    is StepSpec.DeleteDir,
    is StepSpec.CleanWs,
    is StepSpec.Unstable,
    is StepSpec.Pwd,
    is StepSpec.Load,
    is StepSpec.AnsiColor,
    is StepSpec.Milestone,
    is StepSpec.IsUnix,
    -> false
}

/**
 * Whether a checkout names a secret.
 *
 * The SCM family is sealed, so this `when` is exhaustive by construction: a new
 * kind of source control has to say here whether it can carry a credential.
 */
private fun Scm.declaresCredentialBinding(): Boolean = when (this) {
    is GitScm -> credentialsId != null
}
