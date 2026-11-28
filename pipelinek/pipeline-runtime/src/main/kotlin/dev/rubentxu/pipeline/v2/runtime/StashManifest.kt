package dev.rubentxu.pipeline.v2.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Physical layout of one stash name inside the run's local store.
 *
 * `<controlRoot>/stashes/<runId>/<name>/` owns three kinds of entries, and the split is
 * what makes a restash safe for readers: `gen-<N>/` directories are immutable snapshots
 * (a `MANIFEST.json` plus a `snapshot/` tree), and the `CURRENT` pointer file is the only
 * mutable node — rewritten atomically once a generation is complete. A reader resolves
 * the pointer once and then reads a tree nobody writes into again; a writer never deletes
 * the tree a reference may still be naming.
 *
 * The pointer content is a generation directory name, not a path: it cannot carry a
 * traversal into another part of the store.
 */
internal object StashStoreLayout {

    /** Name of the atomically-published reference file inside a stash name directory. */
    const val CURRENT_POINTER: String = "CURRENT"

    /** Directory prefix of an immutable generation snapshot. */
    const val GENERATION_PREFIX: String = "gen-"

    /** Directory inside a generation that holds the captured tree. */
    const val SNAPSHOT_DIR: String = "snapshot"

    /** The complete generation directory name for a number. */
    fun generationDir(generation: Int): String = "$GENERATION_PREFIX$generation"

    /** True when [dirName] is exactly the shape a generation directory must have. */
    fun isGenerationDir(dirName: String): Boolean {
        if (!dirName.startsWith(GENERATION_PREFIX)) return false
        val number = dirName.removePrefix(GENERATION_PREFIX)
        return number.isNotEmpty() && number.all(Char::isDigit) && (number.toIntOrNull() ?: 0) >= 1
    }

    /** The generation number a pointer content names, or null when it names none. */
    fun pointerGeneration(text: String): Int? {
        val trimmed = text.trim()
        return if (isGenerationDir(trimmed)) trimmed.removePrefix(GENERATION_PREFIX).toInt() else null
    }
}

/**
 * Tree manifest of one published stash generation.
 *
 * The rows are [CaptureReceipt]s — the same verified capture the durable archive tier
 * mints: capture identity, visible name, digest and size READ BACK from the stored
 * target, logical run/stage identity, no physical path. The stash reuses that verified
 * capture but stays the run's temporary tier: the manifest is an on-disk audit record of
 * the snapshot, not an entry in any durable artifact index.
 *
 * Immutability: a manifest is written once inside its generation directory, before the
 * reference moves to it, and the generation is never written into afterwards. [writeTo]
 * publishes the file atomically so a crash mid-write can never leave a reader holding a
 * truncated manifest.
 */
data class StashManifest(
    val name: String,
    val generation: Int,
    val rows: List<CaptureReceipt>,
) {
    init {
        require(name.isNotBlank()) { "StashManifest requires the stash name it describes" }
        require(generation >= 1) { "StashManifest generation must be a positive number, got $generation" }
        require(rows.map { it.name.value }.distinct().size == rows.size) {
            "StashManifest carries one row per stored path"
        }
        require(rows.all { it.runId.isNotBlank() }) {
            "StashManifest rows must carry the run identity that captured them"
        }
        require(rows.map { it.captureId }.distinct().size == rows.size) {
            "StashManifest rows must each carry their own capture identity"
        }
    }

    /** The manifest as the JSON document stored inside the generation directory. */
    fun toJson(): String {
        val files: JsonArray = buildJsonArray {
            for (row in rows) {
                add(
                    buildJsonObject {
                        put("captureId", JsonPrimitive(row.captureId.value))
                        put("relPath", JsonPrimitive(row.name.value))
                        put("sha256", JsonPrimitive(row.blobId.value))
                        put("sizeBytes", JsonPrimitive(row.size))
                        put("runId", JsonPrimitive(row.runId))
                        put("stageName", JsonPrimitive(row.stageName))
                    },
                )
            }
        }
        val document: JsonObject = buildJsonObject {
            put("kind", JsonPrimitive(KIND))
            put("name", JsonPrimitive(name))
            put("generation", JsonPrimitive(generation))
            put("files", files)
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * Stores the manifest at `<genDir>/MANIFEST.json` through an atomic move: the file is
     * either absent or complete, never half-written.
     */
    fun writeTo(genDir: Path): Path {
        val target = genDir.resolve(MANIFEST_FILE_NAME)
        val temp = Files.createTempFile(genDir, "$MANIFEST_FILE_NAME.", ".tmp")
        try {
            Files.writeString(temp, toJson())
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
        return target
    }

    companion object {
        const val KIND: String = "stash-manifest"
        const val MANIFEST_FILE_NAME: String = "MANIFEST.json"

        private val strictJson = Json { isLenient = false }

        /** Reads the manifest a generation directory carries, refusing a malformed one. */
        fun readFrom(genDir: Path): StashManifest =
            fromJson(Files.readString(genDir.resolve(MANIFEST_FILE_NAME)))

        /**
         * Parses the stored JSON back into a manifest. A document of another kind, a row
         * without its capture identity, without a canonical digest, or a duplicate path is
         * a refusal: the manifest is the audit record of the snapshot, and a record that
         * does not parse says the generation cannot be trusted.
         */
        fun fromJson(text: String): StashManifest {
            val document: JsonObject = strictJson.parseToJsonElement(text).jsonObject
            require(document["kind"]?.jsonPrimitive?.content == KIND) {
                "not a stash manifest: kind '${document["kind"]?.jsonPrimitive?.content}'"
            }
            val rows = (document.getValue("files") as JsonElement).jsonArray.map { element ->
                val row = element.jsonObject
                CaptureReceipt(
                    captureId = ArtifactId(row.getValue("captureId").jsonPrimitive.content),
                    runId = row.getValue("runId").jsonPrimitive.content,
                    stageName = row.getValue("stageName").jsonPrimitive.content,
                    name = ArtifactName(row.getValue("relPath").jsonPrimitive.content),
                    blobId = BlobId(row.getValue("sha256").jsonPrimitive.content),
                    size = row.getValue("sizeBytes").jsonPrimitive.content.toLong(),
                )
            }
            return StashManifest(
                name = document.getValue("name").jsonPrimitive.content,
                generation = document.getValue("generation").jsonPrimitive.content.toInt(),
                rows = rows,
            )
        }
    }
}
