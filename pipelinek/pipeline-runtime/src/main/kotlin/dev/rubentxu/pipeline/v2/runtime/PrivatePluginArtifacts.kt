package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The private copy of every plugin artifact, and the guarantee that the copy is what runs.
 *
 * ## The gap this closes
 *
 * The runtime measures an artifact's bytes, admits the artifact on that measurement, and then
 * LOADS it — and between the measurement and the load there is a window in which the file on
 * disk can be replaced. A second measurement did not close that window: it read the file again
 * and then handed THAT path to the classloader, so the bytes the classloader finally read were
 * read after the last measurement. A digest measured once and trusted later proves nothing about
 * the file that is actually loaded.
 *
 * ## What replaces the second measurement
 *
 * The artifact is COPIED into a directory only the operator can read, the COPY's digest is
 * measured and compared with the digest that was admitted, and the COPY is what the classloader
 * is given. The copy sits inside a directory with mode 0700, so the bytes that were verified are
 * the bytes that are read: there is no window between the verification and the load, because the
 * thing verified IS the thing loaded.
 *
 * ## Why the copy keeps the file's name
 *
 * The classloader is given a FILE URL, and the JDK reads a file URL as a JAR because the file is
 * not a directory. A copy renamed to something else could stop being read as the JAR it is, so
 * the copy keeps the name it had and only gains the digest in front of it — the digest is what
 * makes two artifacts with the same name two different copies instead of one overwritten by the
 * other.
 *
 * ## Why a refusal and not a warning
 *
 * A copy whose digest disagrees with the admitted one means the artifact changed between the
 * moment its bytes were measured and the moment they were copied. The composition does not
 * start: a plugin whose bytes are not the bytes that were admitted is not the plugin the
 * operator asked for, and the runtime would be about to run it anyway.
 *
 * The same is true of the directory the copies go into. Its permissions are imposed and then
 * verified, and a directory that does not hold the profile afterwards is a refusal: a copy that
 * cannot be hidden from the other users of the machine is a copy that can be swapped between
 * the moment it is verified and the moment it is loaded.
 *
 * ## What this does NOT do
 *
 * It does not protect the operator's ORIGINAL artifact, which stays where they put it with the
 * permissions they gave it. The copy is what the RUN loads; the original is the operator's file.
 */
internal object PrivatePluginArtifacts {

    /**
     * What the copies are.
     *
     * There is no partial copy: an installation that cannot verify one of its artifacts does
     * not compose over the others either, because a composition that quietly loaded three of a
     * plugin's four artifacts would look like a working installation that lost a feature.
     */
    sealed interface CopyOutcome {

        /** Every artifact has a verified private copy, and these are the artifacts to compose over. */
        data class Copied(val artifacts: List<AdmittedArtifact>) : CopyOutcome

        /** One artifact could not be copied with the bytes that were admitted. */
        data class Refused(
            val artifact: Path,
            val rejection: PluginManifestRejection,
            val detail: String,
        ) : CopyOutcome
    }

    /**
     * Copy every admitted artifact into [into], and verify each copy.
     *
     * @param admitted the artifacts the admission approved, in the order they were admitted.
     * @param measured the digest the runtime measured over each artifact's bytes, by path. These
     *   are the digests the admission decided on, so a copy is verified against the DECISION and
     *   not against a fresh reading of the same file.
     * @param into the directory the copies go into. It is created with the permissions the
     *   profile requires, and it is the only thing standing between the copies and the other
     *   users of the machine.
     * @param permissions how the directory's permissions are imposed and verified. The port is
     *   here so that the refusal has a witness: a directory that cannot be secured is a
     *   directory the runtime must refuse, and only a double that fails on purpose can make
     *   that happen on demand.
     */
    fun copyAll(
        admitted: List<AdmittedArtifact>,
        measured: Map<Path, Digest>,
        into: Path,
        permissions: DirectoryPermissions = PosixDirectoryPermissions,
    ): CopyOutcome {
        if (admitted.isEmpty()) return CopyOutcome.Copied(emptyList())

        val directory = when (val prepared = privateDirectory(into, permissions)) {
            is PrivateDirectory.Prepared -> prepared.path

            is PrivateDirectory.NotSecured -> {
                val artifact = admitted.first().path
                return CopyOutcome.Refused(
                    artifact = artifact,
                    rejection = PluginManifestRejection.PrivateCopyNotSecured(
                        artifact = artifact.toString(),
                        detail = prepared.detail,
                    ),
                    detail = "the private copy of $artifact could not be put where only the operator " +
                        "can reach it, so no copy was made: ${prepared.detail}",
                )
            }
        }

        val copied = mutableListOf<AdmittedArtifact>()

        for (artifact in admitted) {
            val expected = measured[artifact.path]
                ?: return CopyOutcome.Refused(
                    artifact = artifact.path,
                    rejection = PluginManifestRejection.ArtifactNotMeasurable(
                        artifact = artifact.path.toString(),
                        detail = "the artifact was admitted without a measurement, so no copy of it " +
                            "can be verified against the bytes that were admitted",
                    ),
                    detail = "the artifact $artifact was admitted without a measured digest, so " +
                        "there is nothing to verify a copy against",
                )

            val copy = directory.resolve(copyNameOf(artifact.path, expected))
            try {
                copyBytes(artifact.path, copy)
            } catch (e: SymbolicLinkInArtifact) {
                return CopyOutcome.Refused(
                    artifact = artifact.path,
                    rejection = PluginManifestRejection.ArtifactNotMeasurable(
                        artifact = artifact.path.toString(),
                        detail = "the artifact contains a symbolic link at ${e.path}, and a link can " +
                            "point outside the artifact, so the copy could not be made from the " +
                            "artifact's own content",
                    ),
                    detail = "the artifact $artifact contains a symbolic link at ${e.path}, and a link " +
                        "can point outside the artifact, so it is not copied",
                )
            } catch (e: IOException) {
                return CopyOutcome.Refused(
                    artifact = artifact.path,
                    rejection = PluginManifestRejection.ArtifactNotMeasurable(
                        artifact = artifact.path.toString(),
                        detail = "the artifact could not be copied, so its bytes cannot be verified " +
                            "at the moment they are about to be loaded: ${e.message}",
                    ),
                    detail = "the artifact $artifact could not be copied into a private directory: " +
                        "${e.message}",
                )
            }

            val found = PluginManifestResourceReader.digestOfArtifactAt(copy)
            if (found == null || !found.value.equals(expected.value, ignoreCase = true)) {
                return CopyOutcome.Refused(
                    artifact = artifact.path,
                    rejection = PluginManifestRejection.ArtifactChangedDuringAdmission(
                        artifact = artifact.path.toString(),
                        measuredDigest = expected.value,
                        foundDigest = found?.value ?: "<unreadable at copy time>",
                    ),
                    detail = "the artifact $artifact changed between the moment its bytes were " +
                        "measured and the moment they were copied, so the copy is not the artifact " +
                        "that was admitted",
                )
            }

            copied += artifact.copy(path = copy)
        }

        return CopyOutcome.Copied(copied)
    }

    /**
     * The directory the copies live in, created so that only the operator can reach it.
     *
     * The permissions are set on the DIRECTORY and not on each copy: a directory nobody else can
     * traverse hides every file inside it, and a copy of a plugin JAR is not a secret the
     * operator asked to be protected from themselves — it is a file that must not be swapped
     * between the moment it is verified and the moment it is loaded.
     *
     * The permissions are IMPOSED and then VERIFIED, and a directory that does not hold the
     * profile afterwards is a refusal rather than a warning: a copy that cannot be hidden from
     * the other users of the machine is a copy that can be swapped between the moment it is
     * verified and the moment it is loaded, which is the whole thing the copy exists to prevent.
     */
    private fun privateDirectory(into: Path, permissions: DirectoryPermissions): PrivateDirectory {
        try {
            Files.createDirectories(into)
        } catch (e: IOException) {
            return PrivateDirectory.NotSecured("the private directory $into could not be created: ${e.message}")
        }

        val imposed = permissions.impose(into)
        if (imposed != null) return PrivateDirectory.NotSecured(imposed)

        if (!permissions.verify(into)) {
            return PrivateDirectory.NotSecured("the permissions of $into are not the ones the profile requires")
        }

        return PrivateDirectory.Prepared(into)
    }

    /**
     * The name a copy gets: the digest in front, the original name behind.
     *
     * The digest is what makes the name unique — two artifacts can share a file name and differ
     * in their bytes — and the original name is what keeps the copy readable as the JAR it is.
     */
    private fun copyNameOf(artifact: Path, digest: Digest): String {
        val hex = digest.value.removePrefix("sha256:").takeIf { it.isNotBlank() } ?: digest.value
        val name = artifact.fileName?.toString().orEmpty().ifBlank { "artifact" }
        return "$hex-$name"
    }

    /** The bytes of the artifact, copied whole: a file, or the tree of an exploded build. */
    private fun copyBytes(from: Path, to: Path) {
        if (Files.isDirectory(from)) {
            Files.walkFileTree(
                from,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (Files.isSymbolicLink(dir)) throw SymbolicLinkInArtifact(dir.toString())
                        Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (Files.isSymbolicLink(file)) throw SymbolicLinkInArtifact(file.toString())
                        Files.copy(
                            file,
                            to.resolve(from.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } else {
            if (Files.isSymbolicLink(from)) throw SymbolicLinkInArtifact(from.toString())
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * The private directory, either secured or not.
     *
     * There is no third case: a directory whose permissions could not be imposed and verified
     * is a directory the copies do not go into.
     */
    private sealed interface PrivateDirectory {

        /** The directory exists and holds the permissions the profile requires. */
        data class Prepared(val path: Path) : PrivateDirectory

        /** The directory could not be secured, and this is why. */
        data class NotSecured(val detail: String) : PrivateDirectory
    }

    /**
     * A symbolic link inside the artifact, which the copy refuses to follow.
     *
     * A link can point anywhere, so copying it would copy whatever it points at, and the copy
     * would no longer be the artifact that was measured.
     */
    private class SymbolicLinkInArtifact(val path: String) :
        IOException("the artifact contains a symbolic link at $path")
}
