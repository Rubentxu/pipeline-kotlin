package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalRunPolicy
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.output.OutputRetentionPort
import dev.rubentxu.pipeline.v2.output.RetainUntil
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions

/**
 * The facts a run's SHELL transport and OUTPUT retention derive, and the ONE place they are
 * derived.
 *
 * ## Why these two and not the others
 *
 * A run receives facts, and most of them are facts it USES: the journal it writes, the registry
 * it resolves against, the clock it reads. Those belong to the ports the run holds.
 *
 * These two are different. The shell transport and the output retention are **values the run
 * computes from the facts it was given**, and both used to be parameters that a caller could
 * contradict:
 *
 * ```text
 * shOptions       ← a caller could pass a workspace root that was not the run's
 * outputRetention ← a caller could pass a policy that deletes what the product keeps
 * ```
 *
 * The first is a wrong path; the second is a wrong lifetime. Neither is a capability the caller
 * legitimately owns, so neither belongs in the signature. Deriving them here means the run's
 * transport is a **consequence** of the policy the caller was given, and a reviewer reads the
 * consequence in one place instead of reconstructing it from a constructor call.
 *
 * ## What is NOT derived here
 *
 * The retry and waitUntil control journals are NOT derived, and the reason is a side effect:
 * `FileBasedRetryControlJournal` creates its directory in its constructor, so deriving one would
 * write to disk at composition time — in a unit test, with no run to justify it. Those two stay
 * parameters, and a composition that wants them says so.
 */

/**
 * The SHELL transport of a run, derived from the policy it was given.
 *
 * Every field below is a **consequence** of one field of the policy, and the derivation is
 * total: there is no second place where a workspace root or a network decision can be written.
 */
internal fun canonicalShellOptions(policy: CanonicalRunPolicy): ShOptions = ShOptions(
    // With --workspace <dir>, the project's own directory IS the workspace — stages share it
    // (Jenkins-familiar semantics). Adding .resolve("workspace") would point to a subdirectory
    // of the project root (typically nonexistent), and every `sh` step would fail with
    // "No such file or directory" because gradlew/mvn/node live in the project root itself.
    // Without --workspace we keep the legacy per-stage layout.
    workspaceRoot = policy.workspace.base ?: policy.controlDirRoot.resolve("workspace"),
    captureStdout = false,
    timeoutMs = null,
    env = emptyMap(),
    // The profile is the policy's; the EXTRA paths and the kept paths are the ambient
    // configuration the resolver reads, and they are read in ONE place so that the transport
    // cannot disagree with itself about which sandbox it is.
    sandbox = SandboxConfigResolver.resolve(policy.sandboxProfile),
    // Carry the ownership the CLI decided into the runtime transport, so the destructive guard
    // reads the real owner of this root instead of re-deriving `Managed`.
    workspaceOwnership = policy.workspace.ownership,
    // Egress is DENIED unless the operator asked for it with --allow-network. The bridge turns
    // the operator's request into the generic NETWORK_EGRESS_CAPABILITY, and a step that needs
    // the network declares it — so the default run cannot reach one. The two ends are gates, not
    // verdicts: `--allow-network` is [AllowAll] and the default is [DenyAll], which are the SAME
    // interface as a future per-destination allowlist — so adding one later is a new value here
    // and not a new code path through the runtime, the step, or the admission check.
    networkEgress = if (policy.allowNetwork) AllowAll else DenyAll,
)

/**
 * The retention of a run's output, derived from the store it would act on.
 *
 * ## Why [RetainUntil.ExplicitReleaseOnly] and not a parameter
 *
 * The run's terminal state is the only thing that can authorise discarding its output, and the
 * policy that says which terminal states may do so is a **product** decision, not a caller's:
 *
 * - `pipeline console --control-dir` reads the transcript of a run that ALREADY FINISHED, so a
 *   `RunTerminalPlus` here would delete the very output the product exists to serve;
 * - the operator-release surface that would exercise `OperatorReleased` is not built yet, so a
 *   policy that authorised a release would authorise one with nobody to ask.
 *
 * The consequence is stated rather than hidden: **nothing releases automatically.** A policy
 * fixed at the composition is a value a reviewer can read, and the day an operator-release
 * surface exists it becomes a policy with a second case — a new value, not a new code path
 * through the runtime.
 *
 * ## Why the store arrives as a SUPPLIER
 *
 * The store is reached only if the policy actually authorises a release. Recovering the plane is
 * not free, and a policy that retains everything must not pay for — nor be perturbed by — a
 * recovery it never uses. [RetainUntil.ExplicitReleaseOnly] authorises nothing at all, so with
 * this derivation the supplier is never invoked.
 */
internal fun canonicalOutputRetention(store: () -> OutputRetentionPort): RunOutputRetention =
    RunOutputRetention(
        retention = store,
        policy = RetainUntil.ExplicitReleaseOnly,
    )
