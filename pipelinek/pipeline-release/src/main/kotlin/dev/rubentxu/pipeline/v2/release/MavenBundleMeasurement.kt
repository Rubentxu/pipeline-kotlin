package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * What was measured in a bundle: the artifacts a consumer resolves, and the
 * digest of the whole thing.
 */
data class MavenBundleState(
    /** The artifacts of the recognised kinds, ordered by path. */
    val artifacts: List<MavenArtifactDigest>,
    /** The digest over every file of the bundle. */
    val sha256: String,
)

/**
 * The measurement of a Maven bundle and of the repository it is published to.
 *
 * The measurement is where the digests come from. Nothing in this file
 * believes a digest: every one of them is computed from the bytes, and the
 * only digests that are read rather than computed are the ones the manifest
 * and the request carry — those are the CLAIMS, and the comparison between a
 * claim and a measurement is the whole point of the decision.
 *
 * The digest of a bundle is a ROOT over its files: the SHA-256 of the lines
 * `"<sha256>  <path relative to the bundle root>"`, sorted by path and each
 * one terminated by a newline. The rule is here and only here, so that the
 * producer that writes a manifest and the door that checks it cannot
 * disagree about what "the bundle" means. A file that is not an artifact — a
 * signature, a checksum — is not in the root's input, so a bundle with a
 * tampered signature is a bundle with a different digest.
 */
object MavenBundleMeasurement {

    /**
     * Measure the bundle at [root].
     *
     * Every file under [root] takes part in the root digest; the files of
     * the recognised kinds also become artifacts, ordered by path so that two
     * measurements of the same bundle produce the same list.
     */
    fun measure(root: Path): MavenBundleState {
        val files = walk(root)
        val digests = files.associate { it to Sha256.ofFile(root.resolve(it)) }
        val artifacts = files.mapNotNull { path ->
            val kind = kindOf(path) ?: return@mapNotNull null
            MavenArtifactDigest(path, kind, digests.getValue(path))
        }.sortedBy { it.path }
        return MavenBundleState(artifacts, rootDigest(digests))
    }

    /**
     * What the repository at [root] holds at [version].
     *
     * The version is looked up by its own name wherever it appears in the
     * layout, and not by a path the caller assembles: the caller does not
     * know how the group is spelled, and a path assembled from a spelling
     * that is wrong reads a repository that does not exist and reports it as
     * a repository without the version.
     *
     * An absent repository is an absent version. A repository that could not
     * be read is NOT an absent version, and the caller has to tell the two
     * apart: it does so by refusing to call this function on a repository it
     * could not open.
     */
    fun measurePublished(root: Path, version: String): MavenPublishedVersion {
        require(Files.isDirectory(root)) {
            "the target repository at $root is not a directory, so its state was not measured."
        }
        val versionDirectories = Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it) && it.fileName.toString() == version }
                .toList()
        }
        if (versionDirectories.isEmpty()) return MavenPublishedVersion.Absent

        val artifacts = versionDirectories.flatMap { directory ->
            val prefix = root.relativize(directory).toString().replace('\\', '/')
            walk(directory).mapNotNull { path ->
                val kind = kindOf(path) ?: return@mapNotNull null
                val relative = "$prefix/$path"
                MavenArtifactDigest(relative, kind, Sha256.ofFile(directory.resolve(path)))
            }
        }.distinctBy { it.path }.sortedBy { it.path }

        return MavenPublishedVersion.Present(artifacts)
    }

    /**
     * The contracts the candidate's SDK ZIP carries: its POM entries, with
     * the digest of each one.
     *
     * These are the contracts the candidate was admitted with, and the
     * comparison between them and the manifest's POMs is what proves that
     * the bundle publishes the candidate's contracts and not others.
     */
    fun contractsIn(sdkZip: Path): List<MavenArtifactDigest> {
        val contracts = mutableListOf<MavenArtifactDigest>()
        ZipFile(sdkZip.toFile()).use { archive ->
            val entries = archive.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.endsWith(".pom")) continue
                val digest = archive.getInputStream(entry).use { digestOf(it) }
                contracts += MavenArtifactDigest(name, MavenArtifactKind.POM, digest)
            }
        }
        return contracts.sortedBy { it.path }
    }

    /**
     * The relative paths of every file under [root], sorted.
     *
     * The paths are relative and use `/` as the separator, so that the
     * measurement of a bundle does not depend on where the build ran.
     */
    private fun walk(root: Path): List<String> =
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }

    /**
     * The kind of a file, by what it is called.
     *
     * The names are the ones a Maven repository uses, and they are the ones
     * a consumer's resolver asks for by name. A file that is not one of them
     * has no kind, and a file with no kind is not an artifact: it is pinned
     * by the bundle's root digest and by nothing else.
     */
    fun kindOf(path: String): MavenArtifactKind? = when {
        path.endsWith(".pom") -> MavenArtifactKind.POM
        path.endsWith("-sources.jar") -> MavenArtifactKind.SOURCES
        path.endsWith("-javadoc.jar") -> MavenArtifactKind.JAVADOC
        path.endsWith(".jar") -> MavenArtifactKind.JAR
        else -> null
    }

    /**
     * The root of a set of digests: the SHA-256 of the sorted lines
     * `"<sha256>  <path>"`, one per line.
     *
     * The hex and the digest come from [Sha256], the one SHA-256 utility of
     * this repository: a digest computable two ways is the kind of ambiguity
     * that lets a wrong bundle pass.
     */
    private fun rootDigest(digests: Map<String, String>): String {
        val preimage = buildString {
            digests.entries.sortedBy { it.key }.forEach { (path, sha256) ->
                append(sha256).append("  ").append(path).append('\n')
            }
        }
        return Sha256.ofBytes(preimage.toByteArray(StandardCharsets.UTF_8))
    }

    /** The SHA-256 of a stream's bytes. */
    private fun digestOf(stream: InputStream): String = Sha256.ofBytes(stream.readBytes())
}
