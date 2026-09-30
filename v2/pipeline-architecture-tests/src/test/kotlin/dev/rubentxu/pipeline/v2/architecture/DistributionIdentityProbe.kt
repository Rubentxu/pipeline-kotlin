package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarInputStream
import java.util.zip.ZipInputStream

/**
 * P0.3b — the effectful half of the identity gate: read a real distribution ZIP
 * and produce [DistributionIdentityFacts] for the pure decision.
 *
 * The split is deliberate and follows AGENTS.md ("separate decision from
 * interpretation"). [DistributionIdentity] decides with no I/O; this collects
 * facts from bytes on disk. Neither half invents the other's job: the probe
 * never decides, and the decision never touches a filesystem.
 *
 * Every surface is probed independently and may come back null. A surface that
 * cannot be read is reported as unobserved, never defaulted to the expected
 * version. Defaulting would let a missing JAR manifest masquerade as a
 * passing check, which is the exact false-green this gate exists to prevent.
 */
object DistributionIdentityProbe {

    /** The application JAR is the one carrying the runtime's own manifest. */
    const val APPLICATION_JAR_PATTERN = "lib/pipeline-application-"

    /**
     * Collect the three ZIP-derived surfaces:
     * [IdentitySurface.ASSET], [IdentitySurface.ARCHIVE_ROOT] and
     * [IdentitySurface.IMPLEMENTATION_VERSION].
     *
     * Returns only the surfaces it could actually observe. The caller is
     * responsible for merging these with the build-time and runtime
     * observations, because those two are not derivable from the ZIP alone.
     */
    fun probeZip(zip: Path): List<IdentityObservation> = listOf(
        IdentityObservation(IdentitySurface.ASSET, assetVersion(zip)),
        IdentityObservation(IdentitySurface.ARCHIVE_ROOT, archiveRootVersion(zip)),
        IdentityObservation(IdentitySurface.IMPLEMENTATION_VERSION, jarImplementationVersion(zip)),
    )

    /** `pipelinek-<V>.zip` -> `<V>`. Null when the name does not match. */
    fun assetVersion(zip: Path): String? {
        val name = zip.fileName?.toString() ?: return null
        if (!name.startsWith("pipelinek-") || !name.endsWith(".zip")) return null
        return name.removePrefix("pipelinek-").removeSuffix(".zip").takeIf { it.isNotEmpty() }
    }

    /**
     * The single top-level directory inside the archive, as `pipelinek-<V>`.
     * A ZIP with zero or several top-level roots is malformed for our
     * distribution shape and yields null rather than a guess.
     */
    fun archiveRootVersion(zip: Path): String? {
        val roots = topLevelEntries(zip)
        if (roots.size != 1) return null
        val root = roots.single()
        if (!root.startsWith("pipelinek-")) return null
        return root.removePrefix("pipelinek-").removeIfEmpty()
    }

    /**
     * `Implementation-Version` from the primary application JAR's manifest.
     * Reads the manifest through the JAR's own entry API rather than assuming
     * the manifest is the first or only entry, which ZIP ordering does not
     * guarantee.
     */
    fun jarImplementationVersion(zip: Path): String? {
        if (!Files.isRegularFile(zip)) return null
        ZipInputStream(Files.newInputStream(zip)).use { outer ->
            var entry = outer.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.contains(APPLICATION_JAR_PATTERN) &&
                    name.endsWith(".jar")
                ) {
                    val nested = outer.readBytes()
                    return readJarImplementationVersion(nested)
                }
                outer.closeEntry()
                entry = outer.nextEntry
            }
        }
        return null
    }

    private fun readJarImplementationVersion(jarBytes: ByteArray): String? {
        JarInputStream(jarBytes.inputStream()).use { jar ->
            return jar.manifest?.mainAttributes?.getValue("Implementation-Version")
        }
    }

    private fun topLevelEntries(zip: Path): Set<String> {
        if (!Files.isRegularFile(zip)) return emptySet()
        val roots = linkedSetOf<String>()
        ZipInputStream(Files.newInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.trimEnd('/')
                if (name.isNotEmpty()) {
                    roots.add(name.substringBefore('/'))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return roots
    }

    private fun String.removeIfEmpty(): String? = takeIf { it.isNotEmpty() }
}
