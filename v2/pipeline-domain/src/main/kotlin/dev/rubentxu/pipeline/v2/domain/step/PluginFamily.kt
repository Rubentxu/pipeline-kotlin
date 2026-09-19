package dev.rubentxu.pipeline.v2.domain.step

/**
 * Functional classification of a plugin (PLUGIN_IDENTITY_MODEL).
 *
 * Multi-family: a single plugin MAY belong to several families (e.g.
 * `pipeline-git` belongs to SCM and NETWORK). The set is open: plugins
 * may declare values that the runtime does not know about by carrying
 * the family name as a free-form string in [StepProviderMetadata]
 * extensions. The enum here is the **canonical default** set; the
 * runtime does NOT branch on it (Delivery is metadata, not a verdict).
 */
enum class PluginFamily {
    SCM,
    NETWORK,
    CONTAINERS,
    ARTIFACTS,
    TESTING,
    UTILITIES,
    SUPPLY_CHAIN,
    REPORTING,
    CREDENTIALS,
}
