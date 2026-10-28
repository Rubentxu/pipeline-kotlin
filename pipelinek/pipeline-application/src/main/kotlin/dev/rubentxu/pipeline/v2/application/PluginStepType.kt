package dev.rubentxu.pipeline.v2.application

/**
 * The short, human-facing type of a step, derived from its structural key.
 *
 * A plugin id is namespaced (`core.sh`, `core.file.writeFile`); the durable
 * protocol publishes a SHORT label (`sh`, `file`) in the step lifecycle
 * context, so an observer can tell which family a step belongs to without
 * parsing the key.
 *
 * This is a pure derivation of a string: it consults no table, no registry and
 * no decoded command. It used to live inside the legacy core metadata table,
 * which is gone; it survives on its own because it is the ONLY thing that
 * needed it, and the metadata table it was hiding inside is what carried no
 * consumer.
 */
object PluginStepType {

    /**
     * Short display type derived from a plugin id (e.g. `core.sh` -> `sh`,
     * `core.file.writeFile` -> `file`).
     *
     * A key without a `core.` prefix keeps its own first segment, so an
     * external plugin id (`acme.report`) yields `acme`.
     */
    fun shortType(pluginId: String): String = pluginId.removePrefix("core.").substringBefore(".")
}
