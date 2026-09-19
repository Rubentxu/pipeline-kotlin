package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef

/**
 * Immutable artifact identity for one release of a plugin
 * (PLUGIN_IDENTITY_MODEL).
 *
 * The release binds a logical [plugin] identity (the namespace/identity
 * pair) to a concrete [version] and [digest]. Two releases of the same
 * plugin with the same version but different digests are distinct
 * artifacts and MUST NOT be collapsed.
 */
data class PluginReleaseRef(
    val plugin: ResourceRef,
    val version: SemVer,
    val digest: Digest,
) {
    init {
        require(plugin.kind == dev.rubentxu.pipeline.v2.domain.identity.ResourceKind.PLUGIN) {
            "PluginReleaseRef.plugin must be a PLUGIN ResourceRef (was ${plugin.kind})"
        }
    }
}
