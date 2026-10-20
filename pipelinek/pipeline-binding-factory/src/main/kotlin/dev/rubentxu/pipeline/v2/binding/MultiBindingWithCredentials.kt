package dev.rubentxu.pipeline.v2.binding

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.util.ServiceLoader

/**
 * Orchestrates credential binding resolution using SPI-discovered [ContributedBindingFactory] instances.
 *
 * ## Design (INV-L6-CR-010 — Parallel Multi-Binding Isolation)
 *
 * Each contributed factory is loaded independently via `ServiceLoader` and handles
 * only its supported kinds. This provides:
 * - **Isolation**: a failure in one factory's resolution does NOT affect other factories
 * - **Parallelism-ready**: factories can be invoked in parallel (future optimization)
 * - **Extensibility**: new binding kinds can be added without modifying this class
 *
 * ## Fail-Fast Semantics (Partial-Failure = Nothing Injected)
 *
 * [resolveAll] follows all-or-nothing semantics:
 * 1. Resolve ALL bindings to their env entries
 * 2. If ANY resolution fails, return failure WITHOUT injecting any env vars
 * 3. Only commit (return success) when ALL bindings are resolved
 *
 * This ensures a malformed credential cannot leave the process in a partially-injected state.
 *
 * @param serviceLoader Optional [ServiceLoader] for testing; defaults to system loader
 */
class MultiBindingWithCredentials(
    private val serviceLoader: ServiceLoader<ContributedBindingFactory> = ServiceLoader.load(
        ContributedBindingFactory::class.java
    )
) {

    private val factories: Map<String, ContributedBindingFactory> by lazy {
        val entries = mutableListOf<Pair<String, ContributedBindingFactory>>()
        for (factory in serviceLoader.iterator()) {
            for (kind in factory.supportedKinds()) {
                entries.add(kind to factory)
            }
        }
        entries.associate { (kind, factory) -> kind to factory }
    }

    /**
     * Returns the set of all supported binding kinds.
     */
    fun supportedKinds(): Set<String> = factories.keys

    /**
     * Resolves a single [CredentialsBinding] to environment variable entries.
     *
     * @param binding The binding to resolve
     * @param credentialResolver Function that resolves a [CredentialsId] to a [SecretHandle]
     * @return List of [ContributedBindingFactory.EnvEntry] entries
     * @throws UnsupportedBindingKindException if no factory handles this binding kind
     * @throws BindingResolutionException if resolution fails
     */
    fun resolve(
        binding: CredentialsBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        val factory = factories[binding.kind]
            ?: throw UnsupportedBindingKindException(binding.kind, factories.keys)

        return try {
            factory.resolve(binding, credentialResolver)
        } catch (e: BindingResolutionException) {
            throw e
        } catch (e: Exception) {
            throw BindingResolutionException(binding, e.message ?: "Unknown error", e)
        }
    }

    /**
     * Resolves ALL bindings with fail-fast semantics.
     *
     * ## Fail-Fast (INV-L6-CR-010)
     *
     * If ANY binding fails to resolve:
     * 1. All successfully resolved entries are discarded (zero injected)
     * 2. [BindingResolutionException] is thrown with the first failure
     * 3. No environment variables are added to the process
     *
     * This prevents partial injection where some credentials are set and others aren't.
     *
     * @param bindings List of bindings to resolve
     * @param credentialResolver Function that resolves a [CredentialsId] to a [SecretHandle]
     * @return Map of environment variable names to secret handles (ONLY on success)
     * @throws BindingResolutionException if any binding fails (injects nothing)
     */
    fun resolveAll(
        bindings: List<CredentialsBinding>,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): Map<String, SecretHandle> {
        if (bindings.isEmpty()) return emptyMap()

        val resolvedEntries = mutableListOf<ContributedBindingFactory.EnvEntry>()
        var firstFailure: BindingResolutionException? = null

        // Phase 1: Resolve ALL bindings (isolation per INV-L6-CR-010)
        for (binding in bindings) {
            try {
                val entries = resolve(binding, credentialResolver)
                resolvedEntries.addAll(entries)
            } catch (e: BindingResolutionException) {
                firstFailure = e
                break // fail-fast: stop on first failure
            } catch (e: UnsupportedBindingKindException) {
                firstFailure = BindingResolutionException(binding, e.message ?: "Unsupported kind", e)
                break
            } catch (e: Exception) {
                firstFailure = BindingResolutionException(binding, e.message ?: "Unknown error", e)
                break
            }
        }

        // Phase 2: If ANY failure occurred, inject NOTHING
        if (firstFailure != null) {
            // Wipe any handles we resolved before the failure
            resolvedEntries.forEach { entry ->
                try {
                    entry.handle.close()
                } catch (_: Exception) {
                    // Ignore wipe failures during abort
                }
            }
            throw firstFailure
        }

        // Phase 3: Success — return all entries
        return resolvedEntries.associate { it.name to it.handle }
    }

    /**
     * Convenience overload for resolving with a simple credential map.
     *
     * @param bindings List of bindings to resolve
     * @param credentialsMap Map from [CredentialsId] to [SecretHandle]
     * @return Map of environment variable names to secret handles
     * @throws BindingResolutionException if any binding fails
     */
    fun resolveAll(
        bindings: List<CredentialsBinding>,
        credentialsMap: Map<CredentialsId, SecretHandle>
    ): Map<String, SecretHandle> {
        return resolveAll(bindings) { id ->
            credentialsMap[id] ?: throw BindingResolutionException(
                bindings.first { it.credentialsId == id },
                "Credential '${id.value}' not found in credentials map"
            )
        }
    }
}
