@file:OptIn(ExperimentalEncodingApi::class)

package pipeline.kotlin.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


class LookupException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun String.systemPropertyLookup(): Result<String> {
    return try {
        System.getProperty(this)?.let { success(it) }
            ?: throw LookupException("System property not found for key: $this")
    } catch (e: Exception) {
        failure(LookupException("Error in system property lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.environmentVariableLookup(): Result<String> {
    return try {
        success(System.getenv(this) ?: throw LookupException("Environment variable not found for key: $this"))
    } catch (e: Exception) {
        failure(LookupException("Error in environment variable lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.base64Lookup(): Result<String> {
    return try {
        val encoded = Base64.encode(this.toByteArray())
        success(encoded)
    } catch (e: Exception) {
        failure(LookupException("Error in base64 lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.fileBase64Lookup(): Result<String> {
    return try {
        val content = Files.readAllBytes(Path.of(this))
        val encoded = Base64.encode(content)
        success(encoded)
    } catch (e: Exception) {
        failure(LookupException("Error in file base64 lookup: ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.decodeBase64Lookup(): Result<String> {
    return try {
        val decoded = Base64.decode(this)
        success(decoded.decodeToString())
    } catch (e: Exception) {
        failure(LookupException("Error in base64 lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.fileLookup(): Result<String> {
    return try {
        val content = String(Files.readAllBytes(Path.of(this)), StandardCharsets.UTF_8)
        success(content)
    } catch (e: Exception) {
        failure(LookupException("Error in file string lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.readFileLookup(): Result<String> {
    return try {
        val content = String(Files.readAllBytes(Path.of(this)), StandardCharsets.UTF_8)
        success(content)
    } catch (e: Exception) {
        failure(LookupException("Error in file string lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.readFileBase64Lookup(): Result<String> {
    return try {
        val content = Files.readAllBytes(Path.of(this))
        val encoded = Base64.encode(content)
        success(encoded)
    } catch (e: Exception) {
        failure(LookupException("Error in file base64 lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

inline fun <reified T> String.deserializeJson(): Result<T> {
    return try {
        val dataObject = Json.decodeFromString<T>(this)
        success(dataObject)
    } catch (e: Exception) {
        failure(LookupException("Error in JSON lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.deserializeJsonField(): Result<String> {
    return try {
        val components = this.split(":", limit = 2)
        if (components.size < 2) {
            throw IllegalArgumentException("Input string is not in the expected format 'key:json'")
        }

        val jsonFieldName = components[0]
        val json = components[1]
        val jsonObject = Json.parseToJsonElement(json).jsonObject

        jsonObject[jsonFieldName]?.jsonPrimitive?.content?.let {
            success(it)
        } ?: throw LookupException("JSON does not contain the specified key '$jsonFieldName'")
    } catch (e: Exception) {
        failure(LookupException("Error in JSON field lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}


fun Path.deserializeYamlFileToMap(): Result<Map<String, Any>> {
    return try {
        val content = String(Files.readAllBytes(this), StandardCharsets.UTF_8)
        val yaml = Yaml().load(content) as Map<String, Any>
//        val yaml = Yaml(Constructor(HashMap.class, LoaderOptions())).load(content) as Map<String, Any>
        success(yaml)
    } catch (e: Exception) {
        failure(LookupException("Error in YAML lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}



fun String.deserializeYamlField(): Result<String> {
    return try {
        val components = this.split(":", limit = 2)
        if (components.size < 2) {
            throw IllegalArgumentException("Input string is not in the expected format 'key:yaml'")
        }

        val yamlFieldName = components[0]
        val yamlFile = components[1]
        val yaml = yamlFile.readFileLookup().getOrThrow()
        val yamlObject = Yaml().load(yaml) as Map<String, Any>

        yamlObject[yamlFieldName]?.toString()?.let {
            success(it)
        } ?: throw LookupException("YAML does not contain the specified key '$yamlFieldName'")
    } catch (e: Exception) {
        failure(LookupException("Error in YAML field lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

fun String.lookup(): Result<String> {
    val enclosedBy = "\${"
    val enclosedIn = "}"
    val escapedWith = '^'
    val escapeEnclosedBy = "$escapedWith$enclosedBy"

    // Chequea si es una cadena escapada
    if (this.startsWith(escapeEnclosedBy)) {
        return success(this.removePrefix(escapeEnclosedBy).removeSuffix(enclosedIn))
    }

    // Encuentra todos los lookups en la cadena
    val pattern = "\\\$\\{([-\\^\\\"!\\w:\\s\\d\\.\\/\\=\\*\\u00C0-\\u00FF\\u0100-\\u017F\\u0180-\\u024F]+)}".toRegex()
    val patternJson = "\\\$\\{\\s?(json:.*)}".toRegex()
    var currentInput = this.replace("\n", " ")
    var matchResult = pattern.find(currentInput)
    var matchJson = patternJson.find(currentInput)

    while (matchResult != null || matchJson != null) {
        val lookupContent = matchResult?.groups?.get(1)?.value ?: matchJson?.groups?.get(1)?.value ?: ""
        val lookupResult = processLookup(lookupContent).getOrThrow()

        // Reemplaza el primer lookup encontrado con el resultado y busca el siguiente
        currentInput = currentInput.replaceFirst("$enclosedBy$lookupContent$enclosedIn", lookupResult)
        matchResult = pattern.find(currentInput)
        matchJson = patternJson.find(currentInput)
    }
    return success(currentInput)

}

fun String.isJsonValid(): Boolean {
    return try {
        Json.parseToJsonElement(this)
        true
    } catch (e: Exception) {
        false
    }
}

fun Map<String, Any?>.lookup(): Result<Map<String, Any>> {
    return success(this.mapValues { (_, value) ->
        when (value) {
            is String -> value.lookup()
            is Map<*, *> -> (value as Map<String, Any>).lookup()
            else -> success(value)
        } as Any
    })
}

private fun processLookup(value: String): Result<String> {
    val result = when {
        value.startsWith("sysProp:") -> value.removePrefix("sysProp:").systemPropertyLookup()
        value.startsWith("env:") -> value.removePrefix("env:").environmentVariableLookup()
        value.startsWith("file:") -> value.removePrefix("file:").fileLookup()
        value.startsWith("readFile:") -> value.removePrefix("readFile:").fileLookup()
        value.startsWith("base64:") -> value.removePrefix("base64:").base64Lookup()
        value.startsWith("fileBase64:") -> value.removePrefix("fileBase64:").fileBase64Lookup()
        value.startsWith("readFileBase64:") -> value.removePrefix("readFileBase64:").fileBase64Lookup()
        value.startsWith("decodeBase64:") -> value.removePrefix("decodeBase64:").decodeBase64Lookup()
        value.startsWith("json:") -> value.removePrefix("json:").deserializeJsonField()
        value.startsWith("yaml:") -> value.removePrefix("yaml:").deserializeYamlField()
        else -> failure(LookupException("Unknown lookup type for key: $value"))
    }

    if (result.isFailure && value.contains(":-")) {
        val defaultValue = value.substringAfter(":-")
        return success(defaultValue)
    }
    return result
}

