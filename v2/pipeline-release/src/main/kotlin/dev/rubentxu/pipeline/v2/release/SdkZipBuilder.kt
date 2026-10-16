package dev.rubentxu.pipeline.v2.release

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Build the SDK distribution and the SDK BOM JSON manifest for a candidate.
 *
 * §8 of the project policy demands 5 exact release assets, including
 * `pipelinek-<v>.sdk.zip` (compiled public contracts) and
 * `pipelinek-bom-<v>.json` (a JSON view of the Maven BOM). The local
 * Maven repository at `<v2>/build/sdk-repo/` is the single source of
 * truth for what was published; this builder selects the entries for
 * the current product version and packages them.
 *
 * The SDK ZIP contains every JAR and POM whose folder ends with
 * `/<version>/`. The BOM JSON lists the managed coordinates as
 * `group:artifact:version` triples plus a per-file SHA-256 inventory of
 * the ZIP contents (so a downstream consumer can verify the bytes
 * without re-extracting the archive).
 *
 * Both files are written under `<v2>/pipeline-application/build/distributions/`
 * to match the convention `distZip` and `cyclonedxBom` already use. The
 * `candidateAdmission` task then copies them next to the ZIP and
 * records their digests in the manifest.
 */
object SdkZipBuilder {

    /** Schema tag for [BomManifest]; bumped on breaking changes. */
    const val BOM_SCHEMA_VERSION: String = "pipelinek-bom-1"

    /**
     * Build both artifacts and write them under [distDir].
     *
     * @param sdkRepo the local Maven repo produced by
     *   `publishSdkForExternalPlugin` (typically `<v2>/build/sdk-repo/`).
     * @param version the product version, e.g. `0.62.0`. Every folder
     *   under [sdkRepo] whose name matches this version is included.
     * @param managedCoordinates the SDK coordinates the BOM pins, in
     *   `group:artifact` form. The builder completes each with
     *   [version].
     * @param distDir the directory the artifacts are written into.
     * @return the paths of the produced files, in the order
     *   `(sdkZip, bomJson)`.
     */
    fun build(
        sdkRepo: Path,
        version: String,
        managedCoordinates: List<Pair<String, String>>,
        distDir: Path,
    ): Pair<Path, Path> {
        require(version.isNotBlank()) { "version must not be blank" }
        require(managedCoordinates.isNotEmpty()) {
            "managedCoordinates must not be empty: a BOM with no constraints is a defect"
        }
        Files.createDirectories(distDir)

        val entries = collectVersionedEntries(sdkRepo, version)
        require(entries.isNotEmpty()) {
            "no SDK entries found under $sdkRepo for version '$version'; " +
                "did publishSdkForExternalPlugin run?"
        }

        val sdkZip = distDir.resolve("pipelinek-$version.sdk.zip")
        val bomJson = distDir.resolve("pipelinek-bom-$version.json")

        val inventory = writeSdkZip(sdkZip, entries)
        writeBomManifest(bomJson, version, managedCoordinates, inventory)

        return sdkZip to bomJson
    }

    /**
     * Walk [sdkRepo] and return every regular file whose path contains
     * `/<version>/` as a folder segment. Returns the absolute paths in
     * the order they appear on the filesystem; the zip writer preserves
     * a deterministic order via [inventory]'s sort.
     */
    private fun collectVersionedEntries(sdkRepo: Path, version: String): List<Path> {
        if (!Files.isDirectory(sdkRepo)) return emptyList()
        val versionSegment = "/$version/"
        return Files.walk(sdkRepo).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().contains(versionSegment) }
                .sorted()
                .toList()
        }
    }

    /**
     * Write the SDK ZIP at [target] and return the per-file inventory
     * (path relative to [sdkRepo], size, sha256) for the BOM manifest.
     * The inventory is sorted by relative path so the BOM JSON is
     * byte-deterministic across builds.
     */
    private fun writeSdkZip(
        target: Path,
        entries: List<Path>,
    ): List<BomFileEntry> {
        Files.createDirectories(target.parent)
        val inventory = entries.map { absolute ->
            val relative = absolute.toString()
            val bytes = Files.readAllBytes(absolute)
            val sha256 = sha256Hex(bytes)
            BomFileEntry(
                path = relative,
                size = bytes.size.toLong(),
                sha256 = sha256,
            )
        }.sortedBy { it.path }

        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(target))).use { zip ->
            for (file in entries) {
                val name = file.toString()
                val entry = ZipEntry(name)
                entry.size = Files.size(file)
                zip.putNextEntry(entry)
                Files.copy(file, zip)
                zip.closeEntry()
            }
        }
        return inventory
    }

    /**
     * Write the BOM JSON manifest at [target]. The shape is intentionally
     * flat: a list of `group:artifact:version` triples (the resolution
     * surface of the Maven BOM) plus a per-file inventory of the
     * SDK ZIP. Consumers that want a CycloneDX view of the same
     * dependencies use the SBOM; this is the BOM view.
     */
    private fun writeBomManifest(
        target: Path,
        version: String,
        managedCoordinates: List<Pair<String, String>>,
        inventory: List<BomFileEntry>,
    ) {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema_version\": \"").append(BOM_SCHEMA_VERSION).append("\",\n")
        sb.append("  \"product_version\": \"").append(version).append("\",\n")
        sb.append("  \"bom\": {\n")
        sb.append("    \"group\": \"dev.rubentxu.pipeline.v2\",\n")
        sb.append("    \"artifact\": \"pipeline-sdk-bom\",\n")
        sb.append("    \"version\": \"").append(version).append("\"\n")
        sb.append("  },\n")
        sb.append("  \"managed_dependencies\": [\n")
        managedCoordinates.forEachIndexed { idx, (group, artifact) ->
            val comma = if (idx == managedCoordinates.lastIndex) "" else ","
            sb.append("    {\"group\": \"")
                .append(group)
                .append("\", \"artifact\": \"")
                .append(artifact)
                .append("\", \"version\": \"")
                .append(version)
                .append("\"}")
                .append(comma)
                .append("\n")
        }
        sb.append("  ],\n")
        sb.append("  \"files\": [\n")
        inventory.forEachIndexed { idx, entry ->
            val comma = if (idx == inventory.lastIndex) "" else ","
            sb.append("    {\"path\": \"")
                .append(entry.path)
                .append("\", \"size\": ")
                .append(entry.size)
                .append(", \"sha256\": \"")
                .append(entry.sha256)
                .append("\"}")
                .append(comma)
                .append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        Files.writeString(target, sb.toString())
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class BomFileEntry(
        val path: String,
        val size: Long,
        val sha256: String,
    )
}
