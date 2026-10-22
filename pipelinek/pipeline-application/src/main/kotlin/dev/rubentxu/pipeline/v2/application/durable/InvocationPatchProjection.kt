package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.step.BodyContextDerivation
import dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection
import dev.rubentxu.pipeline.v2.domain.step.BodyRuntimeValue
import dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch
import dev.rubentxu.pipeline.v2.domain.step.deriveChildExecutionContext
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The projection of a body invocation's patch onto the surfaces it touches.
 *
 * ## Why a patch has TWO surfaces and not one
 *
 * A patch says "run this body under these conditions". Two things carry those conditions: the
 * [ExecutionContext] (the structural scope) and the [ShOptions] (the options the process is
 * launched with). A projection that only reaches ONE of them is a projection that does not
 * happen: the context would say `FOO=1` while the process runs without `FOO`, and the context
 * would be lying about the world the body lives in.
 *
 * That is not a theoretical concern. It is exactly what happened to
 * [ExecutionContextPatch.Environment]: the patch pushed the values onto the context and the
 * `sh` body never saw them, because a `sh` process is launched with [ShOptions.env] and with
 * nothing else. A body that has to recognise its own operation key — the whole contract of a
 * reconcilable provider — cannot recognise a key it never received.
 *
 * ## The same rule as the scopes
 *
 * The script's own scopes (`dir` / `withEnv`) have always projected onto BOTH surfaces, which
 * is why they work. A patch is the same kind of statement, so it gets the same kind of
 * projection: one function, both surfaces, one rule.
 *
 * ## Refusal is a first-class outcome
 *
 * A patch that cannot be honoured is REFUSED, not silently dropped. A dropped directory patch
 * would run the body in the wrong directory, and a body that believes it is somewhere else is
 * worse than a body that never ran.
 */
internal sealed interface InvocationPatchProjection {

    /** The patch was projected: the body runs with this context and these options. */
    data class Applied(
        val context: ExecutionContext,
        val shOptions: ShOptions,
    ) : InvocationPatchProjection

    /** The patch cannot be honoured, and the body must not run. */
    data class Refused(val reason: String) : InvocationPatchProjection
}

/**
 * Projects a body invocation's patch onto the context and the options of the body it opens.
 *
 * Total: it answers for every patch in the closed [ExecutionContextPatch] family, including the
 * ones it cannot honour. A patch that names a directory outside the workspace is refused, and a
 * credential lease is a no-op because the lease preamble owns it.
 *
 * @param parent the context the body is invoked in.
 * @param options the options the body would be launched with.
 * @param patch the patch the invocation carries.
 */
internal fun projectInvocationPatch(
    parent: ExecutionContext,
    options: ShOptions,
    patch: ExecutionContextPatch,
): InvocationPatchProjection = when (patch) {
    ExecutionContextPatch.None -> InvocationPatchProjection.Applied(parent, options)

    is ExecutionContextPatch.Environment -> {
        val context = contextWith(parent, patch)
        InvocationPatchProjection.Applied(
            context = context,
            shOptions = options.copy(
                // The patch is the MORE SPECIFIC scope, so its values win over the ones the
                // options already carry. The values are plain by construction — the patch's
                // type is `Map<String, String>` — so they enter the typed channel as plain
                // handles, exactly as the `withEnv` scope does.
                env = options.env + patch.values.mapValues { SecretHandle.plain(it.value) },
            ),
        )
    }

    is ExecutionContextPatch.Directory -> {
        val previous = (options.workingDirectory ?: options.workspaceRoot).normalize()
        val target = try {
            resolveContainedDirectory(patch.path, previous)
        } catch (invalid: InvalidPathException) {
            null
        }
        if (target == null) {
            InvocationPatchProjection.Refused(
                "the directory patch '$patch.path' escapes the workspace '$previous'",
            )
        } else {
            InvocationPatchProjection.Applied(
                context = contextWith(parent, patch),
                shOptions = options.copy(workingDirectory = target),
            )
        }
    }

    // The credential lease is projected by the lease preamble, which owns the acquisition and
    // the release. The seam is honest about what it does not do, and says so.
    is ExecutionContextPatch.CredentialLease -> InvocationPatchProjection.Applied(parent, options)
}

/**
 * The context a patch derives, by the SAME pure derivation the scopes use.
 *
 * The parent is preserved (a derivation never mutates the caller's context), and a
 * derivation that is rejected falls through to the parent unchanged.
 */
private fun contextWith(
    parent: ExecutionContext,
    patch: ExecutionContextPatch,
): ExecutionContext = when (patch) {
    is ExecutionContextPatch.Environment -> when (
        val derived = deriveChildExecutionContext(
            parent = parent,
            projection = BodyContextProjection.Environment,
            runtime = BodyRuntimeValue.EnvironmentValue(patch.values),
        )
    ) {
        is BodyContextDerivation.Derived -> derived.context
        is BodyContextDerivation.Rejected -> parent
    }

    is ExecutionContextPatch.Directory -> when (
        val derived = deriveChildExecutionContext(
            parent = parent,
            projection = BodyContextProjection.WorkingDirectory,
            runtime = BodyRuntimeValue.DirectoryValue(patch.path),
        )
    ) {
        is BodyContextDerivation.Derived -> derived.context
        is BodyContextDerivation.Rejected -> parent
    }

    else -> parent
}

/**
 * The directory a `dir` scope — or a directory patch — resolves to, or `null` when it escapes
 * the workspace it has to stay inside.
 *
 * ## Why the containment rule exists
 *
 * `Path.startsWith` compares NAME ELEMENTS and `String.startsWith` compares characters, and
 * they disagree on the sibling that shares a prefix — the sibling an attacker picks, because
 * `/ws/base-evil` begins with `/ws/base` as a string. The rule is the same one the `dir` scope
 * applies, and it lives in ONE place so the two cannot drift apart.
 */
internal fun resolveContainedDirectory(path: String, previous: Path): Path? {
    val candidate = Path.of(path)
    val target = (if (candidate.isAbsolute) candidate else previous.resolve(candidate)).normalize()
    return if (target.startsWith(previous)) target else null
}
