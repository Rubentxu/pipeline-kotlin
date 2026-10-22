package dev.rubentxu.pipeline.v2.application

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
 * The three answers are kept apart on purpose:
 *  - [Resolved] names the artifact, and the comparison can proceed.
 *  - [Missing] means the class declares no code source at all: nothing can be said about
 *    where it came from, so there is nothing to compare it against.
 *  - [Unreadable] means the class does declare a code source, but it is not a file on disk:
 *    the location exists and cannot be turned into a path.
 *
 * Folding the last two into a single "no origin" would lose the difference between a class
 * that declares nothing and a class that declares something the runtime cannot read, and the
 * operator's remedy is different in each case.
 */
internal sealed interface ProviderOrigin {

    /** The class was loaded from a file on disk: the artifact that provides this contribution. */
    data class Resolved(val artifact: Path) : ProviderOrigin

    /** The class declares no code source, so it has no artifact. */
    data object Missing : ProviderOrigin

    /** The class declares a code source that is not a file on disk. */
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
     * being reported as nothing.
     */
    fun of(location: URL?): ProviderOrigin = when {
        location == null -> ProviderOrigin.Missing
        else -> try {
            ProviderOrigin.Resolved(Path.of(location.toURI()).toAbsolutePath().normalize())
        } catch (e: URISyntaxException) {
            ProviderOrigin.Unreadable(location.toString(), e.reason ?: e.message ?: "malformed location")
        } catch (e: RuntimeException) {
            ProviderOrigin.Unreadable(location.toString(), e.message ?: e.javaClass.simpleName)
        }
    }
}
