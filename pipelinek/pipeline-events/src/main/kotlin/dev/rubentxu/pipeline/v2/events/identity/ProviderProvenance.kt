package dev.rubentxu.pipeline.v2.events.identity

import kotlinx.serialization.Serializable

/**
 * Audit-class projection of a [dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata]
 * attached to a [PipelineEventEnvelope] (LFC-2E2-prep / ADR-0092 / C8).
 *
 * Carries the **immutable identifiers** needed to answer "what code ran in
 * this run?" without reconstructing stringly segments at query time:
 *
 * - [pluginPublisher]    : the publisher string from `StepProviderMetadata.publisher`
 * - [pluginNamespace]    : `ResourceRef(PLUGIN).segments.first()`
 * - [pluginIdentity]     : `ResourceRef(PLUGIN).segments.drop(1).joinToString("/")`
 * - [releaseVersion]     : `PluginReleaseRef.version.toString()`
 * - [releaseDigest]      : `PluginReleaseRef.digest.value`
 * - [families]           : sorted set of family names (deterministic)
 *
 * The provenance field on the envelope is **strictly additive** (nullable,
 * default = null). Envelopes emitted by Steps registered via the legacy
 * `register(StepDefinition)` overload have `provenance = null` (C10).
 * Steps registered with provider metadata through
 * `register(StepRegistration)` have their provenance projected on
 * audit events (C8).
 *
 * The provenance is the typed audit projection, NOT the
 * `StepProviderMetadata` itself; the durable engine and event consumers
 * never see typed plugin classes. The shape is closed and serialised
 * as part of the envelope (V1 wire form with optional `provenance`
 * field).
 */
@Serializable
data class ProviderProvenance(
    val pluginPublisher: String,
    val pluginNamespace: String,
    val pluginIdentity: String,
    val releaseVersion: String,
    val releaseDigest: String,
    val families: Set<String>,
)
