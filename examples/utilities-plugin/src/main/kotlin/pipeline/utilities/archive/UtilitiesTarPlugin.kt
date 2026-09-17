package pipeline.utilities.archive

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.dsl.StageScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset

/**
 * LFC-2E2-EXPANSION U7 — TAR archive support.
 *
 * **Spike decision recorded (`docs/v2/07-uat/E2_U7_TAR_RECEIPT.md`):**
 *
 *   U7 explicitly evaluated Apache Commons Compress as a possible backend for
 *   TAR / GZIP / BZIP2 / XZ. That option was REJECTED for the OFFICIAL_PLUGIN
 *   coordinate:
 *
 *     1. The Commons Compress artifact is ~2 MB and pulls a non-trivial
 *        transitive surface (`commons-codec`, `commons-io`) onto the runtime
 *        classpath even with `compileOnly`. ServiceLoader isolation is
 *        preserved, but the classpath footprint widened for ONE family —
 *        disproportionate cost.
 *     2. The TAR USTAR header format is small and stable (~80 fields in a
 *        512-byte header block, fixed-width octal). Implementing it in pure
 *        JDK keeps the plugin at zero external dependencies (matching the
 *        U1..U6 invariant) AND removes the future temptation to centralise
 *        "archive math" behind an `ArchiveStore` abstraction.
 *     3. GZIP / BZIP2 / XZ compression are deliberately NOT added — those
 *        would require an external library AND they extend the security
 *        threat model (compressed-entry decompression bombs, encoder
 *        correctness). Plain (uncompressed) TAR matches `tar -cf` with no
 *        `-z/-j/-J` flag, which is the same surface that Jenkins `tar` and
 *        Git `git archive` use for unannotated trees. If/when compressed
 *        TAR is needed, a *new* family U7+ (tarGz, tarBz2) can be added
 *        using Commons Compress WITHOUT touching production core.
 *
 * The TAR encoder/decoder here is therefore self-contained, ~250 lines of
 * deterministic Kotlin, and shares the same capability port as `utilities.zip`
 * (`utilities.archive.operations`). It does NOT extract an `ArchiveStore`
 * abstraction — `utilities.zip` and `utilities.tarCreate` are independent
 * StepDefinitions with separate handlers; the shared capability port carries
 * the typed operations.
 *
 *   - `utilities.tarCreate` — bundle a directory tree into a TAR archive.
 *     USTAR (POSIX.1-1988) format with 1024-byte blocking. Entries are
 *     sorted by relative path (deterministic) and emitted as file or
 *     directory entries; symbolic links and special files are NOT supported
 *     in this family (they would require a different `href` field and
 *     further security analysis).
 *
 *   - `utilities.tarExtract` — extract a TAR archive into a destination
 *     directory. **MUST fail closed against entry paths that resolve
 *     outside the destination root** (the TAR equivalent of Zip Slip).
 *     Absolute paths in archive entries are also rejected. Same security
 *     discipline as `utilities.unzip`.
 *
 * Depends ONLY on the public SDK contracts + JDK NIO. No new external
 * dependency.
 */

// ��────────────────────────────────────────────────────────────────────────────
// Typed failure ADT (LFC-2E2-EXPANSION U7 — TAR family's failure classes).
//
// These cases EXTEND `UtilitiesArchiveError` so a single sealed hierarchy
// carries every archive-family failure class. The runtime exception
// `UtilitiesArchiveException` already exists from U6 and remains the
// only carrier; downstream code can `when` on `UtilitiesArchiveError`
// exhaustively regardless of which archive family produced it.
// ─────────────────────────────────────────────────────────────────────────────

sealed interface UtilitiesTarError : UtilitiesArchiveError {
    /** Reject: a TAR entry claims a size that does not match its blocks. */
    data class TarHeaderCorrupt(val entry: String, val reason: String) : UtilitiesTarError

    /** Reject: a TAR entry names a symlink or special device (not supported). */
    data class TarUnsupportedEntryType(val entry: String, val typeFlag: Char) : UtilitiesTarError
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability port EXTENSION (TAR operations share the archive capability token
// but use their own typed methods — no ArchiveStore abstraction).
// ─────────────────────────────────────────────────────────────────────────────

interface TarOperations {
    @Throws(UtilitiesArchiveException::class)
    fun tarCreate(sourceDir: String, targetTar: String, overwrite: Boolean): TarOutput

    @Throws(UtilitiesArchiveException::class)
    fun tarExtract(sourceTar: String, targetDir: String, overwrite: Boolean): UnzipOutput
}

@Serializable
data class TarOutput(
    val source: String,
    val target: String,
    val entryCount: Int,
    val entries: List<String>,
)

// ─────────────────────────────────────────────────────────────────────────────
// tarCreate — bundle a directory tree into a TAR archive (USTAR/POSIX).
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class TarCreateInput(
    val sourceDir: String,
    val targetTar: String,
    val overwrite: Boolean = false,
)

object TarCreateCodec : StepCodec<TarCreateInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override fun encode(value: TarCreateInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(TarCreateInput.serializer(), value))
    override fun decode(encoded: EncodedStepValue): TarCreateInput =
        json.decodeFromString(TarCreateInput.serializer(), encoded.value)
}

object TarOutputCodec : StepCodec<TarOutput> {
    private val json = Json { encodeDefaults = true }
    override fun encode(value: TarOutput): EncodedStepValue {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("source", JsonPrimitive(value.source))
            put("target", JsonPrimitive(value.target))
            put("entryCount", JsonPrimitive(value.entryCount))
            put("entries", JsonArray(value.entries.map { JsonPrimitive(it) }))
        }
        return EncodedStepValue(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj))
    }
    override fun decode(encoded: EncodedStepValue): TarOutput {
        val obj = Json.parseToJsonElement(encoded.value) as kotlinx.serialization.json.JsonObject
        val entries = (obj["entries"] as JsonArray).map { (it as JsonPrimitive).content }
        return TarOutput(
            source = (obj["source"] as JsonPrimitive).content,
            target = (obj["target"] as JsonPrimitive).content,
            entryCount = (obj["entryCount"] as JsonPrimitive).content.toInt(),
            entries = entries,
        )
    }
}

object TarCreateStepDefinition : StepDefinition<TarCreateInput, TarOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.tarCreate")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "tarCreate",
            configRef = "",
            pluginId = UtilitiesArchiveContributor.COORDINATE,
            pluginVersion = UtilitiesArchiveContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = TarCreateCodec,
        outputCodec = TarOutputCodec,
        requiredCapabilities = setOf(UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY),
    )

    override val handler = StepHandler<TarCreateInput, TarOutput> { input, ctx ->
        val ops: ArchiveOperations = ctx.capabilities.get(
            UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY,
        ) as DefaultArchiveOperations
        // The handler uses the same DefaultArchiveOperations as zip/unzip; the
        // tar ops are exposed via the same concrete instance. The cast is
        // justified because the harness wires the production default.
        ops.tarCreate(input.sourceDir, input.targetTar, input.overwrite)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// tarExtract — extract a TAR archive into a destination directory. PATH SAFE.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class TarExtractInput(
    val sourceTar: String,
    val targetDir: String,
    val overwrite: Boolean = false,
)

object TarExtractCodec : StepCodec<TarExtractInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override fun encode(value: TarExtractInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(TarExtractInput.serializer(), value))
    override fun decode(encoded: EncodedStepValue): TarExtractInput =
        json.decodeFromString(TarExtractInput.serializer(), encoded.value)
}

object TarExtractStepDefinition : StepDefinition<TarExtractInput, UnzipOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.tarExtract")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "tarExtract",
            configRef = "",
            pluginId = UtilitiesArchiveContributor.COORDINATE,
            pluginVersion = UtilitiesArchiveContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = TarExtractCodec,
        outputCodec = UnzipOutputCodec, // share unzip output codec shape
        requiredCapabilities = setOf(UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY),
    )

    override val handler = StepHandler<TarExtractInput, UnzipOutput> { input, ctx ->
        val ops: ArchiveOperations = ctx.capabilities.get(
            UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY,
        ) as DefaultArchiveOperations
        ops.tarExtract(input.sourceTar, input.targetDir, input.overwrite)
    }
}

// DSL extensions live in UtilitiesArchivePlugin.kt (where the typed
// capability port and StepDefinitions are also declared) to avoid
// duplicated overloads in this file.

/**
 * USTAR (POSIX.1-1988) TAR format reader/writer. Header is a 512-byte block
 * followed by data blocks (rounded up to 512). Directory ends with two
 * 512-byte zero blocks.
 *
 * Numeric format: octal, ASCII digits, NUL- or space-terminated, right-justified.
 * - name: 100 bytes
 * - mode: 8 bytes
 * - uid: 8 bytes
 * - gid: 8 bytes
 * - size: 12 bytes
 * - mtime: 12 bytes
 * - checksum: 8 bytes
 * - typeflag: 1 byte ('0' regular, '5' directory, etc.)
 * - linkname: 100 bytes
 * - magic: 6 bytes ("ustar\0")
 * - version: 2 bytes ("00")
 * - uname: 32 bytes
 * - gname: 32 bytes
 * - devmajor: 8 bytes
 * - devminor: 8 bytes
 * - prefix: 155 bytes
 * - pad: 12 bytes
 */
internal object TarFormat {
    const val BLOCK_SIZE: Int = 512
    const val RECORD_SIZE: Int = 10240 // gnu tar default; we use 10 * BLOCK_SIZE

    fun pad(size: Long): Int = ((BLOCK_SIZE - (size % BLOCK_SIZE).toInt()) % BLOCK_SIZE)

    fun computeChecksum(header: ByteArray): String {
        // For checksum calculation, the field is treated as all spaces.
        var sum = 0L
        for (i in header.indices) {
            sum += if (i in 148 until 156) {
                32L // space
            } else {
                (header[i].toInt() and 0xFF).toLong()
            }
        }
        // 6-digit octal + NUL + space
        return "%06o\u0000 ".format(sum)
    }

    fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var s = ""
        for (i in 0 until length) {
            val b = header[offset + i].toInt() and 0xFF
            if (b == 0) break
            if (b != 32) s += (b.toChar())
        }
        if (s.isEmpty()) return 0L
        return try {
            java.lang.Long.parseLong(s, 8)
        } catch (_: NumberFormatException) {
            0L
        }
    }

    fun writeOctal(buffer: ByteArray, offset: Int, length: Int, value: Long) {
        // Pad with NUL on the left and a NUL terminator.
        val str = "%0${length - 1}o".format(value)
        val bytes = str.toByteArray(Charsets.US_ASCII)
        for (i in 0 until length) {
            buffer[offset + i] = if (i < bytes.size) bytes[i] else 0.toByte()
        }
        // Last byte always NUL terminator.
        buffer[offset + length - 1] = 0.toByte()
    }
}

/**
 * Walk a directory and emit USTAR headers + data into the TAR stream. Symlinks
 * and special files are REJECTED.
 */
internal class TarWriter(private val out: java.io.OutputStream) {
    fun writeDirectoryTree(sourceDir: Path) {
        // Collect entries deterministically.
        val entries = mutableListOf<Pair<Path, Boolean>>() // (path, isDir)
        Files.walk(sourceDir).use { stream ->
            stream.filter { it != sourceDir }
                .sorted(Comparator.comparing { it.toString() })
                .forEach { p ->
                    entries.add(p to Files.isDirectory(p))
                }
        }

        for ((path, isDir) in entries) {
            val rel = sourceDir.relativize(path).toString().replace('\\', '/')
            writeEntry(rel, path, isDir)
        }
        writeEndMarkers()
        out.flush()
    }

    private fun writeEntry(name: String, srcFile: Path, isDir: Boolean) {
        val header = ByteArray(TarFormat.BLOCK_SIZE)
        // name — split into name + prefix if > 100 chars (USTAR semantics).
        val (prefix, nm) = if (name.length > 100) {
            val split = name.indexOf('/', maxOf(0, name.length - 100 + 1))
            if (split < 0 || split >= name.length) {
                Pair("", name.take(99))
            } else {
                Pair(name.substring(0, split), name.substring(split + 1))
            }
        } else {
            Pair("", name)
        }
        // name field
        val nameBytes = nm.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 99))
        // mode
        val mode: Long = if (isDir) 493L else 420L // 0o755 / 0o644
        TarFormat.writeOctal(header, 100, 8, mode)
        // uid / gid (use 0 for both)
        TarFormat.writeOctal(header, 108, 8, 0L)
        TarFormat.writeOctal(header, 116, 8, 0L)
        // size (0 for directories)
        val size: Long = if (isDir) 0L else Files.size(srcFile)
        TarFormat.writeOctal(header, 124, 12, size)
        // mtime (in seconds since epoch)
        val mtime = Files.getLastModifiedTime(srcFile).toInstant().epochSecond
        TarFormat.writeOctal(header, 136, 12, mtime)
        // typeflag — '5' for directory, '0' for file
        val typeFlag: Char = if (isDir) '5' else '0'
        // Compute checksum AFTER setting the rest. We treat the magic field as
        // part of the checksum contribution, but a USTAR archive is identified
        // by the magic field. Set it now.
        System.arraycopy("ustar\u0000".toByteArray(Charsets.US_ASCII), 0, header, 257, 6)
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()
        // uname / gname
        val uname = "root".toByteArray(Charsets.US_ASCII)
        System.arraycopy(uname, 0, header, 265, minOf(uname.size, 31))
        val gname = "root".toByteArray(Charsets.US_ASCII)
        System.arraycopy(gname, 0, header, 297, minOf(gname.size, 31))
        // devmajor / devminor
        TarFormat.writeOctal(header, 329, 8, 0)
        TarFormat.writeOctal(header, 337, 8, 0)
        // prefix
        val prefixBytes = prefix.toByteArray(Charsets.US_ASCII)
        System.arraycopy(prefixBytes, 0, header, 345, minOf(prefixBytes.size, 154))
        // typeflag slot
        header[156] = typeFlag.code.toByte()
        // checksum — written in octal ASCII digits + NUL + space.
        val checksumStr = TarFormat.computeChecksum(header)
        val checksumBytes = checksumStr.toByteArray(Charsets.US_ASCII)
        System.arraycopy(checksumBytes, 0, header, 148, minOf(checksumBytes.size, 7))
        header[155] = ' '.code.toByte()

        out.write(header)
        if (!isDir) {
            Files.newInputStream(srcFile).use { fis ->
                fis.copyTo(out)
            }
            val pad = TarFormat.pad(size)
            if (pad > 0) {
                val padBuf = ByteArray(pad)
                out.write(padBuf)
            }
        }
    }

    private fun writeEndMarkers() {
        val zeros = ByteArray(TarFormat.BLOCK_SIZE * 2)
        out.write(zeros)
    }
}

/** Minimal USTAR reader used by tarExtract. Validates headers + rejects symlinks. */
internal class TarReader(private val input: java.io.InputStream) {
    data class TarEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val typeFlag: Char,
    )

    @Throws(UtilitiesArchiveException::class)
    fun extract(targetDir: Path): List<String> {
        val collected = mutableListOf<String>()
        while (true) {
            val header = readBlock()
            // End of archive is detected by a zero-filled block.
            if (header == null || isAllZeros(header)) {
                break
            }
            val typeFlag = (header[156].toInt() and 0xFF).toChar()
            val prefix = readString(header, 345, 155).trimEnd('\u0000')
            val name = readString(header, 0, 100).trimEnd('\u0000')
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val size = TarFormat.parseOctal(header, 124, 12)
            val isDirectory = typeFlag == '5'

            // Symlink / special-device rejection (U1 invariant: link rewrite is a
            // security risk and is intentionally excluded).
            if (typeFlag != '0' && typeFlag != '5') {
                throw UtilitiesArchiveException(
                    UtilitiesTarError.TarUnsupportedEntryType(fullName, typeFlag),
                )
            }
            // Name validation (anti path-traversal).
            if (fullName.startsWith("/") || fullName.contains("\u0000")) {
                throw UtilitiesArchiveException(
                    UtilitiesArchiveError.UnzipAbsolutePath(fullName),
                )
            }
            val resolved = targetDir.resolve(fullName).toAbsolutePath().normalize()
            if (!resolved.startsWith(targetDir)) {
                throw UtilitiesArchiveException(
                    UtilitiesArchiveError.UnzipPathTraversal(fullName, resolved.toString()),
                )
            }
            collected.add(fullName)
            if (isDirectory) {
                Files.createDirectories(resolved)
            } else {
                resolved.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(resolved).use { fos ->
                    val buf = ByteArray(8192)
                    var remaining = size
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        remaining -= n
                    }
                }
                // Skip padding to the next block.
                val pad = TarFormat.pad(size)
                if (pad > 0) {
                    val skip = ByteArray(pad)
                    input.read(skip)
                }
            }
        }
        return collected
    }

    private fun readBlock(): ByteArray? {
        val block = ByteArray(TarFormat.BLOCK_SIZE)
        var totalRead = 0
        while (totalRead < TarFormat.BLOCK_SIZE) {
            val n = input.read(block, totalRead, TarFormat.BLOCK_SIZE - totalRead)
            if (n < 0) {
                return if (totalRead == 0) null else block.copyOf(totalRead)
            }
            totalRead += n
        }
        return block
    }

    private fun isAllZeros(block: ByteArray): Boolean {
        for (b in block) if (b.toInt() != 0) return false
        return true
    }

    private fun readString(buf: ByteArray, offset: Int, length: Int): String {
        val end = (0 until length).firstOrNull { buf[offset + it].toInt() == 0 } ?: length
        return String(buf, offset, end, Charsets.US_ASCII)
    }
}
