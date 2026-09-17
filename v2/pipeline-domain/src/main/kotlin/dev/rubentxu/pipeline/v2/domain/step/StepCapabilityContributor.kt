package dev.rubentxu.pipeline.v2.domain.step

/**
 * Public contribution SPI for plugin-owned capability implementations (LFC-2E3-T4).
 *
 * A registry Step declares the capabilities it needs in [StepContract.requiredCapabilities], and
 * is admitted only when EVERY one of them is available at prepare-time
 * (`RegistryExecutionPreparation`), fail-closed before the handler runs. Core capabilities are
 * supplied by the runtime's canonical capability bridge. A capability owned by a plugin has no
 * other legitimate supplier, because production core MUST NOT name a concrete plugin type — that
 * would reverse the hexagonal dependency direction.
 *
 * This interface is therefore the plugin-side half of the capability contract, mirroring how
 * [StepDefinitionContributor] is the plugin-side half of the Step contract.
 *
 * ## Why this is a SEPARATE interface, not a method on [StepDefinitionContributor]
 *
 * Adding a method to an existing interface would be source-compatible but **not
 * binary-compatible**: Kotlin emits interface members with defaults as abstract plus a
 * `DefaultImpls` holder in the default compilation mode, so any already-built plugin JAR would
 * fail at runtime with `AbstractMethodError` the moment the host called the new method. A
 * separate SPI keeps every existing plugin JAR loadable and contributing exactly as before, and
 * lets each class keep a single responsibility.
 *
 * ## Discovery
 *
 * The domain MUST NOT call [java.util.ServiceLoader] itself: discovery is a runtime-adapter
 * concern. The SAME single discovery adapter that loads [StepDefinitionContributor] loads this
 * SPI too, so there is still exactly one ServiceLoader site in production.
 *
 * Contract:
 * - keys MUST be the SAME `StepCapability` values the contributor's StepDefinitions declare;
 * - a plugin that contributes no capability simply does not implement this interface;
 * - duplicate keys across contributors MUST fail closed at composition time, never first-wins
 *   or last-wins.
 */
interface StepCapabilityContributor {
    /** Stable contributor identity (e.g. `pipeline.testing`) used in duplicate diagnostics. */
    val id: String

    /** Implementations of the capability tokens this contributor's StepDefinitions declare. */
    fun capabilities(): Map<StepCapability, Any>
}
