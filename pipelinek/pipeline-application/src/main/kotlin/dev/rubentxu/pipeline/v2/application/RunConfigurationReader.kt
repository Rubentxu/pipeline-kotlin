package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.RunConfiguration
import dev.rubentxu.pipeline.v2.runtime.RunConfigurationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the snapshot of the configuration of a run.
 *
 * ## What the snapshot is
 *
 * A document the SERVICE wrote in the supervision's own directory, with the
 * values the client delivered and the schema of the document in it. The
 * child reads it once, at its start, and the values become the environment
 * of the steps of the run.
 *
 * ## Why the reader VALIDATES what it reads
 *
 * Because the disk is not an authority. The file was written by the service
 * and it sits on the disk, where anything can be: a file that does not
 * carry the schema it claims, or that breaks a rule of the configuration,
 * refuses the run instead of being believed. The rules are the SAME rules
 * the service applied, and they live in ONE place — the type of the
 * configuration — so the reader and the service cannot disagree.
 *
 * ## Why the reader FAILS CLOSED
 *
 * Because a run that was given a configuration and did not get it is a run
 * that ran with an environment nobody asked for. The refusal is the
 * product's own: exit code 2, the code of an argument that was not
 * understood, because that is what a snapshot that cannot be read is.
 */
internal object RunConfigurationReader {

    /** The schema the snapshot carries. */
    const val SCHEMA: String = "pipelinek.runconfig/1"

    /** The origin of a key the start request set itself. */
    const val ORIGIN_REQUEST: String = "request"

    /** The origin of a key a configuration provider supplied. */
    const val ORIGIN_PROVIDER: String = "provider"

    /** The type of a key nobody typed: the request carries text. */
    const val TYPE_TEXT: String = "string"

    /** The origins from the strongest to the weakest: a key the request sets is never asked of a provider. */
    val PRECEDENCE: List<String> = listOf(ORIGIN_REQUEST, ORIGIN_PROVIDER)

    /**
     * The configuration of [path], or the refusal that there is none.
     *
     * A `null` [path] is the configuration that carries nothing, and it is
     * NOT a refusal: a run that was given no configuration is the run that
     * always was.
     */
    fun read(path: String?): RunConfiguration {
        if (path == null) return RunConfiguration.EMPTY
        return load(Path.of(path)).configuration
    }

    /**
     * Where the configuration the snapshot at [path] holds came from, WITHOUT the values.
     *
     * The snapshot is the one store of the configuration of a run, so the description is read from
     * it and not kept anywhere else: what a client is told about a run is what the run was given.
     * A snapshot written without a provider has no provenance, and every key of it is one the
     * request set itself, typed as text.
     *
     * It validates the snapshot as [read] does and refuses the same way.
     */
    fun describe(path: Path): RunConfigurationDescription {
        val snapshot = load(path)
        val values = snapshot.configuration.values
        val provenance = snapshot.provenance
        val entries = values.keys.sorted().map { name ->
            val origin = provenance?.origins?.get(name)
            RunConfigurationEntry(
                name = name,
                origin = origin?.origin ?: ORIGIN_REQUEST,
                type = origin?.type ?: TYPE_TEXT,
            )
        }
        return RunConfigurationDescription(
            digest = configurationDigest(values),
            entries = entries,
            provider = provenance?.provider,
            revision = provenance?.revision,
            sourceDigest = provenance?.sourceDigest,
        )
    }

    /** The description of a run that was given no configuration at all. */
    fun describeNothing(): RunConfigurationDescription =
        RunConfigurationDescription(
            digest = configurationDigest(emptyMap()),
            entries = emptyList(),
            provider = null,
            revision = null,
            sourceDigest = null,
        )

    /** What a snapshot turned out to hold once it was validated. */
    private class Snapshot(val configuration: RunConfiguration, val provenance: RunConfigurationProvenance?)

    /**
     * The snapshot at [file], validated.
     *
     * The values are checked by the rules of the configuration, and when the snapshot says a
     * provider took part, its digest must be the digest of the values it carries: a snapshot whose
     * values were changed after the service wrote it is refused, and so is one whose provenance
     * cannot be read.
     */
    private fun load(file: Path): Snapshot {
        val document = readDocument(file)
        val schema = document.text("schema")
        if (schema != SCHEMA) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration speaks schema '$schema' and " +
                        "this build reads '$SCHEMA'",
                ),
            )
        }
        val values = document.values()
            ?: throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration does not carry a set of values",
                ),
            )
        val configuration = when (val outcome = RunConfiguration.of(values)) {
            is RunConfigurationOutcome.Accepted -> outcome.configuration
            is RunConfigurationOutcome.Refused -> throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration is refused: ${outcome.refusal.reason} " +
                        "(${outcome.refusal.detail})",
                ),
            )
        }
        val provenance = document.provenance(configuration.values)
        return Snapshot(configuration, provenance)
    }

    /**
     * The provenance the document carries, null when it carries none.
     *
     * A document that carries a `provenance` it cannot read, or whose digest is not the digest of
     * its values, is refused: provenance that does not describe the values is worse than none.
     */
    private fun JsonObject.provenance(values: Map<String, String>): RunConfigurationProvenance? {
        val element = this["provenance"] ?: return null
        val refusal = {
            CommandRefused(
                CliExit.InvalidArgument(
                    message = "the provenance of the snapshot of the configuration is not one this " +
                        "build reads or does not describe its values",
                ),
            )
        }
        val provenance = element as? JsonObject ?: throw refusal()
        if (provenance.text("digest") != configurationDigest(values)) throw refusal()
        val origins = LinkedHashMap<String, RunConfigurationOrigin>()
        val raw = provenance["origins"] as? JsonObject ?: throw refusal()
        for ((name, entry) in raw) {
            val item = entry as? JsonObject ?: throw refusal()
            origins[name] = RunConfigurationOrigin(
                origin = item.text("origin") ?: throw refusal(),
                type = item.text("type") ?: throw refusal(),
            )
        }
        return RunConfigurationProvenance(
            provider = provenance.text("provider") ?: throw refusal(),
            revision = provenance.text("revision") ?: throw refusal(),
            sourceDigest = provenance.text("sourceDigest") ?: throw refusal(),
            origins = origins,
        )
    }

    /**
     * The document [file] holds.
     *
     * A file that cannot be read, or that is not a JSON object, refuses the
     * run: the refusal names the file, because the file is what the
     * operator has to fix.
     */
    private fun readDocument(file: Path): JsonObject {
        val text = try {
            Files.readString(file)
        } catch (failure: Exception) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration could not be read: " +
                        (failure.message ?: failure.javaClass.simpleName),
                ),
            )
        }
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration is not a document this build reads",
                ),
            )
        }
        return parsed as? JsonObject
            ?: throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration is not a document this build reads",
                ),
            )
    }

    /**
     * The values the document carries, or null when it carries none it can read.
     *
     * An absent field is an EMPTY set of values, and a field that is not an
     * object of primitives is none at all: a value that cannot be read is
     * not a value that may be applied.
     */
    private fun JsonObject.values(): Map<String, String>? {
        val element = this["values"] ?: return emptyMap()
        val object_ = element as? JsonObject ?: return null
        val values = LinkedHashMap<String, String>()
        for ((name, value) in object_) {
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return null
            values[name] = text
        }
        return values
    }

    /** The text [key] holds, or null when it holds none. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * The document a snapshot of a configuration is written as.
 *
 * It is here so that the WRITER and the READER agree on the shape without
 * sharing a mutable thing: the writer is the service, the reader is the
 * child, and they are two processes that never meet.
 *
 * A [provenance] is written only when a provider took part, so the snapshot of a run that
 * asked none is the one it always was. It carries the digest of the values, which the reader
 * checks, and it never carries a value of its own.
 */
internal fun runConfigurationDocument(
    values: Map<String, String>,
    provenance: RunConfigurationProvenance? = null,
): JsonObject = buildJsonObject {
    put("schema", JsonPrimitive(RunConfigurationReader.SCHEMA))
    put(
        "values",
        buildJsonObject {
            values.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
        },
    )
    if (provenance != null) {
        put(
            "provenance",
            buildJsonObject {
                put("digest", JsonPrimitive(configurationDigest(values)))
                put("provider", JsonPrimitive(provenance.provider))
                put("revision", JsonPrimitive(provenance.revision))
                put("sourceDigest", JsonPrimitive(provenance.sourceDigest))
                put(
                    "origins",
                    buildJsonObject {
                        provenance.origins.forEach { (name, origin) ->
                            put(
                                name,
                                buildJsonObject {
                                    put("origin", JsonPrimitive(origin.origin))
                                    put("type", JsonPrimitive(origin.type))
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}

/** Where one key of a snapshot came from and the type it was validated as. */
internal data class RunConfigurationOrigin(val origin: String, val type: String)

/**
 * Where the values of a snapshot came from when a configuration provider took part.
 *
 * The [sourceDigest] is the digest of what the provider answered, and the [origins] say, key by
 * key, whether the request set it or the provider did. It holds no value.
 */
internal data class RunConfigurationProvenance(
    val provider: String,
    val revision: String,
    val sourceDigest: String,
    val origins: Map<String, RunConfigurationOrigin>,
)

/** One key of a described configuration. */
internal data class RunConfigurationEntry(val name: String, val origin: String, val type: String)

/**
 * What a client may know about the configuration of a run: where each key came from, and the
 * digests that make the snapshot comparable. It holds no value, so it can be shown and logged.
 */
internal data class RunConfigurationDescription(
    val digest: String,
    val entries: List<RunConfigurationEntry>,
    val provider: String?,
    val revision: String?,
    val sourceDigest: String?,
)

/** What looking for the description of the configuration of a run came to. */
internal sealed interface RunConfigurationLookup {
    /** The run is one this service started and its snapshot was read. */
    data class Found(val description: RunConfigurationDescription) : RunConfigurationLookup

    /** The service does not know the run: it did not start it, or it started it before it restarted. */
    data object Unknown : RunConfigurationLookup

    /** The run is known but its snapshot could not be read; [detail] says why. */
    data class Unreadable(val detail: String) : RunConfigurationLookup
}

/** The door the service asks how the configuration of a run came to be. */
internal interface ServiceRunConfigurations {
    /** The description of the configuration [runId] was started with. Never throws. */
    fun describe(runId: String): RunConfigurationLookup
}
