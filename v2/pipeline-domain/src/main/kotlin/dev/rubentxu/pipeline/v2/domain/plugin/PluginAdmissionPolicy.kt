package dev.rubentxu.pipeline.v2.domain.plugin

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * LFC-2E2-PREP (C7/C9, 2026-09-17): the typed admission result for a [PluginManifest].
 *
 * ## Closed result algebra (no boolean + nullable)
 *
 * - [Ready] — the manifest is well-formed AND every declared capability is
 *   satisfied by the runtime. The composition step proceeds.
 * - [MissingCapabilities] — the manifest is well-formed but one or more
 *   declared capabilities cannot be supplied by the runtime. The
 *   composition step is rejected fail-closed BEFORE any handler runs.
 * - [Malformed] — the manifest is structurally invalid (empty families,
 *   duplicate StepKeys, unknown shape, invalid coordinates, etc.). The
 *   composition step is rejected fail-closed.
 *
 * Each case carries its own typed payload; the caller cannot accidentally
 * collapse them into a boolean-and-null. Adding a new case (e.g.
 * `IncompatibleRuntime` for a future capability-version mismatch) forces
 * every `when` over this ADT to be revisited.
 */
sealed interface PluginAdmissionPolicy {

    /** The manifest is admitted; the runtime supplies every declared capability. */
    data class Ready(val declaredCapabilities: Set<StepCapability>) : PluginAdmissionPolicy

    /** The manifest is well-formed but some declared capabilities are missing. */
    data class MissingCapabilities(
        val missing: Set<StepCapability>,
        val declared: Set<StepCapability>,
    ) : PluginAdmissionPolicy

    /** The manifest is structurally invalid. */
    data class Malformed(val reason: String) : PluginAdmissionPolicy
}

/**
 * LFC-2E2-PREP (C9, 2026-09-17): pure admission of a [PluginManifest] given the
 * capabilities the runtime can supply.
 *
 * ## Pure, fail-closed
 *
 * `(manifest, supplied) -> PluginAdmissionPolicy`: no I/O, no clock, no
 * registry mutation, no exception for expected operational outcomes. A
 * malformed manifest is a typed `Malformed`; a missing capability is a typed
 * `MissingCapabilities`; only a fully-satisfied, well-formed manifest returns
 * `Ready`.
 *
 * ## What it checks
 *
 * - Manifest construction already enforces "non-empty families OR contributors"
 *   and "no duplicate StepKeys", so a malformed manifest is usually caught at
 *   construction. This resolver additionally checks that EVERY declared
 *   capability is supplied by the runtime.
 *
 * ## What it does NOT check
 *
 * - It does NOT check that the manifest's families are registered with a
 *   StepRegistry; that is a separate [dev.rubentxu.pipeline.v2.domain.step.StepRegistry.register]
 *   concern, fail-closed at registration.
 * - It does NOT check that the manifest's families are admitted by the
 *   coordinator; that is a separate capability-admission concern (LB-02 / G3-A4.2).
 */
fun resolvePluginAdmissionPolicy(
    manifest: PluginManifest,
    suppliedCapabilities: Set<StepCapability>,
): PluginAdmissionPolicy {
    val declared = manifest.declaredCapabilities
    if (declared.isEmpty()) {
        // A release with no declared capabilities is admitted trivially.
        return PluginAdmissionPolicy.Ready(emptySet())
    }
    val missing = declared - suppliedCapabilities
    return if (missing.isEmpty()) {
        PluginAdmissionPolicy.Ready(declared)
    } else {
        PluginAdmissionPolicy.MissingCapabilities(missing = missing, declared = declared)
    }
}

/**
 * LFC-2E2-PREP (C4, 2026-09-17): closed ADT classifying a plugin family by its
 * CAPABILITY SHAPE.
 *
 * Different from [PluginFamilyShape] (which classifies the execution shape):
 * this ADT classifies the *capability surface* a family requires. A family
 * declaring ATOMIC execution can still require a `WORKSPACE_OPERATIONS`
 * capability, making it a `WorkspaceUser` family. Adding a case here forces
 * every `when` over this ADT to be revisited.
 *
 * Use it for diagnostics, policy selection, and plugin-family registration
 * grouping. The actual capability admission is performed by the typed
 * [StepCapability] tokens declared on each [PluginFamily]; this ADT is the
 * categorical label, not the admission authority.
 */
sealed interface PluginFamilyCapabilityFamily {

    /** Family declares no capabilities. */
    data object Pure : PluginFamilyCapabilityFamily

    /** Family declares a workspace-mutation capability (workspace, delete, clean, write). */
    data object WorkspaceUser : PluginFamilyCapabilityFamily

    /** Family declares a process-execution capability (shell, exec). */
    data object ProcessExecutor : PluginFamilyCapabilityFamily

    /** Family declares an event-sink capability (observability). */
    data object EventEmitter : PluginFamilyCapabilityFamily

    /** Family declares a credential-lease capability. */
    data object CredentialConsumer : PluginFamilyCapabilityFamily

    /** Family declares an artifact-store capability. */
    data object ArtifactProducer : PluginFamilyCapabilityFamily

    /** Family declares multiple capabilities that do not fit the named categories. */
    data class Mixed(val capabilities: Set<StepCapability>) : PluginFamilyCapabilityFamily

    companion object {
        /**
         * Classify a family by inspecting its declared [StepCapability] tokens.
         * Pure decision: no I/O, no clock. Deterministic.
         */
        fun classify(family: PluginFamily): PluginFamilyCapabilityFamily {
            if (family.requiredCapabilities.isEmpty()) return Pure
            val names = family.requiredCapabilities.map { it.key }
            val has = fun(name: String): Boolean = names.any { it.contains(name) }
            // Order matters: Mixed is a fallback when MULTIPLE categories apply,
            // otherwise the single dominant category wins.
            val categories = buildList {
                if (has("workspace") || has("delete-dir") || has("clean-ws") ||
                    has("file-write") || has("temporary-operations")
                ) add(WorkspaceUser)
                if (has("shell") || has("exec") || has("process")) add(ProcessExecutor)
                if (has("event") || has("sink")) add(EventEmitter)
                if (has("credential") || has("lease")) add(CredentialConsumer)
                if (has("artifact") || has("archive")) add(ArtifactProducer)
            }
            return when {
                categories.isEmpty() -> Mixed(family.requiredCapabilities)
                categories.size > 1 -> Mixed(family.requiredCapabilities)
                else -> categories.single()
            }
        }
    }
}

/**
 * Pure check: every PluginStepId in a manifest is unique (no duplicates
 * within a release). Already enforced at [PluginManifest.init], but exposed
 * as a pure function for tests and external validators.
 */
fun manifestStepKeysAreUnique(manifest: PluginManifest): Boolean =
    manifest.stepKeys.size == manifest.stepKeys.toSet().size

/**
 * Pure check: every declared capability in a manifest is supplied by the
 * runtime. Convenience function over [resolvePluginAdmissionPolicy].
 */
fun manifestReady(
    manifest: PluginManifest,
    suppliedCapabilities: Set<StepCapability>,
): Boolean = resolvePluginAdmissionPolicy(manifest, suppliedCapabilities) is PluginAdmissionPolicy.Ready

/**
 * Pure projection: list all [PluginStepId]s contributed by a manifest, in
 * declaration order. Convenience for diagnostics and admission logs.
 */
fun manifestStepKeys(manifest: PluginManifest): List<PluginStepId> = manifest.stepKeys
