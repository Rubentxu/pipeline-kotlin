package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ScriptedStageRef
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint

/**
 * S4-F2 edge — the one place a [ScriptedStageRef] becomes a runnable artifact.
 *
 * ## Why the IR carries a key and the edge carries the object
 *
 * `StageBody.Scripted(ref)` lives in `:pipeline-domain`, which depends on nothing but
 * kotlinx-serialization and coroutines. It therefore cannot hold a
 * `CompiledScriptedEntryPoint`: that is a **host-compiled, live object** — a class, a method
 * handle, a closure — and it is not serialisable. What the IR can hold, and does, is the
 * artifact's own `fingerprintMaterial()`, which the identity already produced and which the
 * replay fingerprint already consumes.
 *
 * The same arrangement `PluginStepId` uses for a Step that lives in a registry: the IR names
 * it, the edge resolves it. Copying the six identity fields into `ScriptedStageRef` would have
 * created a second authority for one identity, and two authorities that decide the same thing
 * get eliminated rather than synchronised.
 *
 * ## Why resolution is an ADT and not a nullable lookup
 *
 * A miss here is not "this stage has no body" — it is a **named, refused** outcome, because the
 * alternative is a scripted stage silently degrading into something else, which is the
 * silent-no-op the semantic constitution forbids. Two distinct refusals, because they have two
 * distinct owners and two distinct repairs:
 *
 * | case | who is wrong | what fixes it |
 * |---|---|---|
 * | [UnknownArtifact] | the pipeline names an artifact nobody compiled | compile and register it, or fix the reference |
 * | [EntryPointMissing] | the artifact exists but not that entry point | fix the reference; the artifact is fine |
 *
 * There is deliberately **no third case for "the registered object is not the one you named"**,
 * because this registry cannot be in that state: the map is keyed by
 * `artifact.fingerprintMaterial()` — the key is COMPUTED from the object, not supplied beside it —
 * so an object filed under a key that is not its own is unrepresentable. A runtime re-check of
 * that would be a guard that cannot fail, which is a claim the code does not make and which
 * `SegmentOutputStore` used to carry in the shape of an `if (false)`. The property is held by the
 * construction and pinned by `ScriptedStageEdgeTest`, not by a second comparison.
 */
sealed interface ScriptedStageResolution {

    /** The named artifact. The only case that may run. */
    data class Resolved(val entryPoint: CompiledScriptedEntryPoint) : ScriptedStageResolution

    /** No compiled artifact is registered under [artifactKey]. */
    data class UnknownArtifact(val artifactKey: String) : ScriptedStageResolution

    /** The artifact is registered, but it does not contain [entryPointId]. */
    data class EntryPointMissing(val artifactKey: String, val entryPointId: String) : ScriptedStageResolution
}

/**
 * The open registry the scripted-stage edge resolves through.
 *
 * Open in the same sense `StepRegistry` is open: a plugin, a harness or the composition root
 * contributes artifacts, and no call site enumerates them. There is no service locator and no
 * global — the coordinator receives one from the composition root, like every other seam.
 */
interface ScriptedStageRegistry {

    /** Resolves [ref] to the artifact it names, or to a named refusal. Total. */
    fun resolve(ref: ScriptedStageRef): ScriptedStageResolution
}

/**
 * The in-process registry, whose lifetime is the run's.
 *
 * ## Why in-process, and why that is not a limitation
 *
 * A `CompiledScriptedEntryPoint` is produced by **host-compiling** lowered source against the
 * plugin classpath. There is no durable form of it, so a process-scoped registry is not a
 * shortcut here — it is the only lifetime the object has. The CLI already works this way: it
 * compiles the artifact for the invocation and hands it to the runner by value.
 *
 * The consequence is stated rather than hidden: **a resumed run re-resolves from the composition
 * root, and only an artifact with the same `fingerprintMaterial()` will be found under the key the
 * pipeline names.** A recompiled artifact that is not byte-identical simply does not resolve, so it
 * is refused as [ScriptedStageResolution.UnknownArtifact] rather than silently substituting itself
 * for the one the run was planned against. That is the same fail-closed rule the standalone
 * scripted frontend already applies to `expectedArtifact`, applied at the edge instead of at the
 * call site.
 */
class InMemoryScriptedStageRegistry(
    artifacts: List<CompiledScriptedEntryPoint> = emptyList(),
) : ScriptedStageRegistry {

    /**
     * Artifacts by fingerprint, and refused on a DUPLICATE rather than overwriting.
     *
     * A duplicate fingerprint means two different compilations claiming one identity, and
     * last-writer-wins would make which one runs depend on registration order. Refusing at
     * registration turns an order-dependent mystery into a startup failure with a name.
     */
    private val byFingerprint: Map<String, CompiledScriptedEntryPoint> = buildMap {
        artifacts.forEach { artifact ->
            val key = artifact.artifact.fingerprintMaterial()
            val existing = put(key, artifact)
            require(existing == null || existing.entryPointId == artifact.entryPointId) {
                "two compiled artifacts claim fingerprint '$key': entry points " +
                    "'${existing?.entryPointId}' and '${artifact.entryPointId}'. The identity is " +
                    "the address, so a duplicate is a build defect, not a last-one-wins choice."
            }
        }
    }

    override fun resolve(ref: ScriptedStageRef): ScriptedStageResolution {
        val artifact = byFingerprint[ref.artifactKey]
            ?: return ScriptedStageResolution.UnknownArtifact(ref.artifactKey)
        if (artifact.entryPointId != ref.entryPointId) {
            return ScriptedStageResolution.EntryPointMissing(ref.artifactKey, ref.entryPointId)
        }
        return ScriptedStageResolution.Resolved(artifact)
    }

    /** The keys this registry can resolve, for a diagnostic that names what IS available. */
    fun registeredKeys(): Set<String> = byFingerprint.keys
}
