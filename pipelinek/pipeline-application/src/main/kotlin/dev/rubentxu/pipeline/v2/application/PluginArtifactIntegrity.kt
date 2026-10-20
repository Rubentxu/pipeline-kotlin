package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import java.nio.file.Path

/**
 * The decisions the runtime makes about the plugin artifacts it was offered, made over the bytes it
 * measured and over nothing else.
 *
 * ## What is decided here
 * Before the runtime may load a class from a plugin artifact, it has to know what that artifact is:
 * whether its bytes can be read at all, which artifacts are the same artifact, and whether the bytes
 * that were read are still the bytes that are about to be loaded. Those are the three questions this
 * file answers, and it answers them over measurements: no clock, no filesystem, no classloader. The
 * composition root does the reading and the loading; this says what the readings mean.
 *
 * ## Why the decisions are separated from the reading
 * The reading is I/O and the decision is arithmetic, and only the second one is worth pinning down.
 * A decision that can be exercised over values in memory can be checked by a test that starts no
 * process, touches no file and loads no class; the same decision written inside the composition
 * root can only be checked by running the whole product against a real artifact. Splitting them
 * keeps the guarantee cheap to observe, and leaves the composition root with nothing to decide.
 *
 * ## What is deliberately not decided here
 * A plugin manifest declares a digest about the payload it ships. That digest is provenance: it says
 * which release the manifest describes. It is NOT the digest of the artifact file, and this runtime
 * never compares the two, because they are different quantities that can never be equal. A plugin
 * build hashes the classes and resources it ships, excluding the two documents that carry the digest
 * itself, so the declared digest answers a question about the plugin's CONTENT while the digest of
 * the artifact file answers a question about the FILE that carries it. Comparing them would report
 * every plugin in existence as lying about itself, and a check that cries wolf on every artifact is
 * not a check.
 *
 * What the runtime does compare is the artifact with itself: the bytes read before admission have
 * to be the bytes that are loaded. That is a question the runtime can answer, and it is answered
 * here.
 */
internal object PluginArtifactIntegrity {

    /**
     * What the runtime could establish about one offered artifact.
     *
     * The two cases are the two things that can happen when bytes are read, and the split is
     * load-bearing: "the bytes are these" and "the bytes could not be read" are different facts,
     * and a model that could only say the first one would let an unreadable artifact pass as a
     * measured one.
     */
    sealed interface Measurement {

        /** The artifact's bytes were read and hashed. */
        data class Measured(val path: Path, val digest: Digest) : Measurement

        /** The artifact's bytes could not be read. */
        data class Unreadable(val path: Path) : Measurement
    }

    /**
     * What the runtime decided about the artifacts it was offered.
     *
     * There is no partial admission. An installation that cannot read one of its artifacts does not
     * compose over the others either: a composition that silently dropped one plugin would look like
     * a working installation that quietly lost a feature.
     */
    sealed interface Selection {

        /** These artifacts may be composed over, in the order they were offered. */
        data class Admitted(val artifacts: List<Measurement.Measured>) : Selection

        /** This artifact's bytes could not be read, so nothing about it can be established. */
        data class NotMeasurable(val artifact: Path) : Selection
    }

    /**
     * The artifact whose bytes are no longer the bytes that were measured.
     *
     * [found] is the digest read at the second measurement, or `null` when the artifact could not be
     * read the second time. A file that became unreadable is a change, not an absence of one.
     */
    data class Changed(val artifact: Path, val measured: Digest, val found: Digest?)

    /**
     * The artifacts the runtime may compose over.
     *
     * Two rules, applied in this order:
     *  1. An artifact whose bytes could not be read stops the selection, and it is named. An
     *     artifact whose bytes are unknown is not an artifact that may be loaded, because the bytes
     *     are the only thing that tells one copy of a plugin from another.
     *  2. Two artifacts with the same bytes are ONE artifact, and the first one offered wins. What
     *     makes two candidates the same artifact is their content, so a plugin named by two paths,
     *     or named twice on the command line, is loaded once rather than twice.
     *
     * @param measurements what the runtime could establish about each offered artifact, in the
     *   order they were offered.
     */
    fun select(measurements: List<Measurement>): Selection {
        val admitted = mutableListOf<Measurement.Measured>()
        val seen = mutableSetOf<String>()

        for (measurement in measurements) {
            when (measurement) {
                is Measurement.Unreadable -> return Selection.NotMeasurable(measurement.path)
                is Measurement.Measured -> {
                    if (seen.add(measurement.digest.value.lowercase())) admitted += measurement
                }
            }
        }

        return Selection.Admitted(admitted)
    }

    /**
     * The artifact whose bytes changed between the two measurements, or `null` when every artifact
     * still holds the bytes that were read.
     *
     * This is the time-of-check/time-of-use seam. A digest measured once and trusted later proves
     * nothing about the file that is actually read, so the bytes are read again before they are
     * loaded and the two readings have to agree.
     *
     * @param before the artifacts as they were measured before admission.
     * @param after the digests read again, by path. A path that is absent counts as unreadable,
     *   because a measurement that is missing is not a measurement.
     */
    fun changed(before: List<Measurement.Measured>, after: Map<Path, Digest?>): Changed? {
        for (measurement in before) {
            val found = after[measurement.path]
            val same = found != null && found.value.equals(measurement.digest.value, ignoreCase = true)
            if (!same) return Changed(measurement.path, measurement.digest, found)
        }
        return null
    }
}
