package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Public StepKey for `core-utils.readJson` (LFC-2E2 utilities OFFICIAL_PLUGIN).
 *
 * The Key is stable; do NOT rename without a deprecation cycle. The codec
 * preserves the value through durable envelopes so a renamed Key would
 * silently break every previously journaled pipeline.
 */
object CoreUtilsReadJsonKey {
    val VALUE: PluginStepId = PluginStepId("core-utils.readJson")
}
