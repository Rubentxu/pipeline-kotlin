package dev.rubentxu.pipeline.v2.application

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Kotlin DSL signature index of the INSTALLED product.
 *
 * Only the core Kotlin API ships in this resource; runtime/plugin capabilities
 * belong to `pipelinek steps --json`. Neither surface evaluates the user's
 * .pipeline.kts. A runtime key is NOT proof of a matching Kotlin overload.
 */
object MainDslCli {
    const val INDEX_USAGE = "Usage: pipelinek dsl index [--name <function>]"
    const val DESCRIBE_USAGE = "Usage: pipelinek dsl describe <function>"
    private const val RESOURCE = "pipelinek/dsl-signatures.json"

    fun index(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val name = when {
            args.isEmpty() -> null
            args.size == 2 && args[0] == "--name" && args[1].isNotBlank() -> args[1]
            else -> {
                err.println(INDEX_USAGE)
                return 2
            }
        }
        return render(name, out, err)
    }

    fun describe(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        if (args.size != 1 || args[0].isBlank() || args[0].startsWith("-")) {
            err.println(DESCRIBE_USAGE)
            return 2
        }
        return render(args[0], out, err)
    }

    private fun render(name: String?, out: PrintStream, err: PrintStream): Int {
        val bytes = MainDslCli::class.java.classLoader.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
        if (bytes == null) {
            err.println("DSL index is absent from this distribution; rebuild the scripting-api resource")
            return 2
        }
        val document = try {
            Json.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        val entries = document?.get("entries") as? JsonArray
        if (document == null || entries == null ||
            document["schema"]?.jsonPrimitive?.content != "pipelinek.dsl-signatures/v1"
        ) {
            err.println("Invalid packaged DSL signature index")
            return 2
        }
        val selected = if (name == null) entries else JsonArray(
            entries.filter { row ->
                (row as? JsonObject)?.get("name")?.jsonPrimitive?.content == name
            },
        )
        if (name != null && selected.isEmpty()) {
            err.println("No core Kotlin DSL signature for '${safeSymbol(name)}'; check pipelinek steps for plugin runtime keys")
            return 2
        }
        val result = JsonObject(document.toMutableMap().apply {
            this["entries"] = selected
        })
        out.println(Json.encodeToString(JsonElement.serializer(), result))
        return 0
    }

    private fun safeSymbol(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(80)
}

internal fun runDslIndexVerb(args: Array<String>, out: PrintStream, err: PrintStream): CliExit =
    verbExit(MainDslCli.index(args, out, err))

internal fun runDslDescribeVerb(args: Array<String>, out: PrintStream, err: PrintStream): CliExit =
    verbExit(MainDslCli.describe(args, out, err))
