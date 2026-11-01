package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputSink

/**
 * The capability key under which the host supplies this plugin with a transcript destination.
 *
 * The key is declared HERE, next to the port it binds, because the manifest, the Step contract
 * and the host's binding must name ONE fact. A key typed in three places is three facts that can
 * drift; a key nobody binds is a Step admitted against a capability that does not exist.
 *
 * It is REQUIRED, not optional: a checkout whose transcript cannot be persisted produces a
 * diagnostic nobody can reconstruct, and the failure is silent in the worst way — the checkout
 * says "authentication failed" and the bytes that would have said WHY are in a private list
 * inside the plugin. An optional capability would let that happen on a host that forgot to bind
 * it, which is exactly the host whose configuration nobody re-reads.
 */
val SCM_GIT_TRANSCRIPT_CAPABILITY: StepCapability = StepCapability("scm-git.transcript")

/**
 * The port a plugin uses to publish what its subprocesses printed.
 *
 * ## Why the host owns the destination
 * The Output Plane's durable authority lives in `pipeline-application`, and this plugin lives
 * BELOW it in the dependency graph. The dependency cannot be inverted, so the destination is
 * INJECTED: the host binds the capability, the plugin asks for it. The plugin never learns which
 * store the bytes land in, and the host never learns how git spells its argv.
 *
 * ## Why the port is a sink per channel and not a byte array
 * The transcript is a STREAM: two channels, bounded chunks, and a reader that may tail it while
 * the command is still running. A `ByteArray` would force the whole output into memory and
 * destroy the reason the transcript exists. A sink per channel keeps the attribution in the
 * channel, exactly as the shell's own transcript keeps it.
 *
 * ## Why the registry travels with the port
 * Redaction is a WRITE-side obligation: a store that redacted on read would already have
 * persisted the secret. So the producer must redact before the bytes leave the plugin, and it
 * needs the run's registry to do it. The registry therefore rides with the destination instead
 * of being a second capability: one capability, one thing the host must get right.
 */
interface GitTranscript {

    /**
     * The destination for one channel. The sink is owned by the host; the plugin writes to it
     * and never closes it. A host that wants an end-of-stream signal asks for it through its own
     * composition — the plugin's redactor, not the sink's lifetime, decides where the tail is.
     *
     * The plugin asks ONCE per channel and KEEPS the answer, because the refusal is read from
     * that very sink: the refusal belongs to the destination that refused the write, and a
     * destination that answered a fresh instance every time would split that answer in two.
     * A hidden refusal is a checkout that claims a transcript nobody can read.
     */
    fun sink(channel: ProcessOutputChannel): ProcessOutputSink

    /**
     * The run's registry, or null when the run has no secrets registered. Null means "there is
     * nothing to redact", NOT "redact nothing but silently": a run without a registry has no
     * secret to leak, and the plugin's diagnostics say so.
     */
    val secretPatterns: SecretPatternRegistry?
}
