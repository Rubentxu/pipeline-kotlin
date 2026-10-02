package dev.rubentxu.pipeline.v2.application

/**
 * Typed input of `core.lock` (RP6-A / WU-091).
 *
 * The WHOLE payload is encoded; the engine does not probe individual fields, and
 * the codec is not required to round-trip only some of them.
 *
 * Not annotated `@Serializable` on purpose: `pipeline-application` does not apply
 * the kotlinx-serialization compiler plugin, so a generated `.serializer()` would
 * not exist. The codec in [CoreLockStep] is written out explicitly, exactly as
 * [CoreShellInput] is.
 *
 * ## Surface and its Jenkins source
 *
 * Derived from the official `lock` step reference
 * (<https://www.jenkins.io/doc/pipeline/steps/lockable-resources/>). The
 * parameters that step also accepts and WU-091 deliberately does NOT expose are
 * listed in `docs/v2/07-uat/SPEC_WU091_LOCK.md` §1 with the reason for each:
 * they need a resource catalogue or an ordered queue, which are RP-8
 * requirements, and `variable` couples this Step to the context system without
 * adding anything to exclusion.
 *
 * ## Coherence is decided once, in one place
 *
 * [skipIfLocked] and [timeoutSeconds] are individually valid and jointly
 * contradictory. [lockIntentOf] turns the pair into a [LockIntent] or a typed
 * [LockInputError]; the handler never re-reads these two fields, so the rule
 * cannot be applied twice with different answers.
 */
data class CoreLockInput(
    val resource: String,
    val timeoutSeconds: Int? = null,
    val reason: String? = null,
    val skipIfLocked: Boolean = false,
)
