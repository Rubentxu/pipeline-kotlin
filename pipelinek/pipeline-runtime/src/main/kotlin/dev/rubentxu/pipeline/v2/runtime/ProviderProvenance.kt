package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.step.Digest
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Path

/**
 * Where a contribution provider came from, as a measured fact with three possible answers.
 *
 * The cross-check that compares a plugin's manifest against what its code actually provides
 * works per ARTIFACT: a plugin is compared only against its own contributions, because a
 * plugin's Steps are "this plugin's" and not "the neighbour's". For that comparison to be
 * possible, every contribution has to say which artifact provided it, and the answer comes
 * from the code the class was loaded from — the one fact the JVM records about every class it
 * defines.
 *
 * ## Why the origin names the CONTENT and not only the address
 * The same artifact lives at more than one address by design. The installation bundles the
 * plugin in its own JAR, and the runtime loads a VERIFIED COPY of it from a private
 * directory; the delegation is parent-first, so the provider's class is fetched by the
 * PARENT and its code source is the bundled JAR, while the artifact the runtime admitted is
 * the copy. Both hold the same bytes, and an origin that named only the address would put
 * the provider's contribution in one bucket and the admitted artifact's manifest in another,
 * and the comparison would then read the manifest against an empty bucket and report a drift
 * that does not exist.
 *
 * A digest is what survives that: the identity of an artifact is its content, and the same
 * bytes measured at another address measure the same. The address is kept alongside it
 * because the operator has to be able to read WHERE the contribution came from, but it is
 * the digest that decides WHICH artifact the contribution belongs to.
 *
 * The three answers are kept apart on purpose:
 *  - [Resolved] names the artifact, and the comparison can proceed.
 *  - [Missing] means the class declares no code source at all: nothing can be said about
 *    where it came from, so there is nothing to compare it against.
 *  - [Unreadable] means the class does declare a code source, but it cannot be named as an
 *    artifact: the location is not a file on disk, or the bytes at it cannot be read.
 *
 * Folding the last two into a single "no origin" would lose the difference between a class
 * that declares nothing and a class that declares something the runtime cannot read, and the
 * operator's remedy is different in each case.
 */
internal sealed interface ProviderOrigin {

    /**
     * The class was loaded from a file on disk: the artifact that provides this contribution,
     * named by the address it was read from and by the digest of its bytes.
     */
    data class Resolved(val artifact: Path, val digest: Digest) : ProviderOrigin

    /** The class declares no code source, so it has no artifact. */
    data object Missing : ProviderOrigin

    /** The class declares a code source that cannot be named as an artifact. */
    data class Unreadable(val location: String, val reason: String) : ProviderOrigin
}

/**
 * The one place where a provider's origin is measured.
 *
 * A class loaded from a JAR or from a classes directory records that location in its
 * protection domain, and that location is the ONLY provenance the JVM keeps about it.
 * Everything else — which loader fetched the class, which manifest mentioned it — is either
 * derived from that same file or is an opinion the runtime would have to defend.
 *
 * The classloader is deliberately NOT consulted, and the reason is worth stating because it
 * looks like the obvious alternative. The composition loader is a child of the application's
 * loader and the delegation is parent-first: a provider that lives in a JAR the installation
 * already bundles is fetched by the PARENT, so its classloader is the application's, exactly
 * like a provider that belongs to the runtime itself. Asking the classloader would answer
 * "runtime" for a bundled plugin's own contribution, and would then hold that plugin's
 * contributions to be nobody's.
 */
internal object ProviderProvenance {

    /**
     * The origin of a class loaded from [location], or the reason there is none.
     *
     * A null [location] is a class that declares no code source. A location that cannot be
     * turned into a path — a scheme this filesystem does not know, a malformed URL — is a class
     * that declares something the runtime cannot read, and it is reported as such instead of
     * being reported as nothing. A location that IS a file but whose bytes cannot be read is
     * the same kind of answer: the runtime has an address and no content, and an address
     * alone cannot identify an artifact.
     *
     * @param measure how the artifact's bytes are read and hashed. The port is here so that the
     *   refusal has a witness: an artifact whose bytes cannot be measured has no origin to
     *   attribute, and only a double that fails on purpose can make that happen on demand.
     */
    fun of(
        location: URL?,
        measure: (Path) -> PluginArtifactDigest.Outcome = PluginArtifactDigest::measure,
    ): ProviderOrigin = when {
        location == null -> ProviderOrigin.Missing
        else -> {
            val path = try {
                Path.of(location.toURI()).toAbsolutePath().normalize()
            } catch (e: URISyntaxException) {
                return ProviderOrigin.Unreadable(
                    location = location.toString(),
                    reason = "it is not a file on disk (${e.reason ?: e.message ?: "malformed location"})",
                )
            } catch (e: RuntimeException) {
                return ProviderOrigin.Unreadable(
                    location = location.toString(),
                    reason = "it is not a file on disk (${e.message ?: e.javaClass.simpleName})",
                )
            }
            when (val outcome = measure(path)) {
                is PluginArtifactDigest.Outcome.Measured -> ProviderOrigin.Resolved(path, outcome.digest)
                is PluginArtifactDigest.Outcome.Refused -> ProviderOrigin.Unreadable(
                    location = location.toString(),
                    reason = outcome.reason.describe(),
                )
            }
        }
    }
}
