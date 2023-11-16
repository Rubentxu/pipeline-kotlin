package dev.rubentxu.pipeline.casc.resolver


import dev.rubentxu.pipeline.logger.PipelineLogger
//import kotlinx.serialization.json.Json
//import kotlinx.serialization.json.jsonObject
//import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.lang3.StringUtils
import org.apache.commons.text.StringSubstitutor
import org.apache.commons.text.TextStringBuilder
import org.apache.commons.text.lookup.StringLookup
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.util.*



/**
 * Resolves secret variables and converts escaped internal variables.
 */
class SecretSourceResolver() {
    private val nullSubstitutor: StringSubstitutor
    private val substitutor: StringSubstitutor

    init {
        var map = mapOf <String, StringLookup> (
                "base64" to Base64Lookup.INSTANCE,
                "fileBase64" to FileBase64Lookup.INSTANCE,
                "readFileBase64" to FileBase64Lookup.INSTANCE,
                "file" to FileStringLookup.INSTANCE,
                "readFile" to FileStringLookup.INSTANCE,
                "sysProp" to SystemPropertyLookup.INSTANCE,
                "env" to EnvironmentVariableLookup.INSTANCE,
                "decodeBase64" to DecodeBase64Lookup.INSTANCE,
                "json" to JsonLookup.INSTANCE
            )

        substitutor = StringSubstitutor(
            FixedInterpolatorStringLookup(
                map, null
            )
        )
            .setEscapeChar(escapedWith)
            .setVariablePrefix(enclosedBy)
            .setVariableSuffix(enclosedIn)
            .setEnableSubstitutionInVariables(true)
            .setPreserveEscapes(true)
        nullSubstitutor = StringSubstitutor(UnresolvedLookup.INSTANCE)
            .setEscapeChar(escapedWith)
            .setVariablePrefix(enclosedBy)
            .setVariableSuffix(enclosedIn)
    }

    /**
     * Encodes String so that it can be safely represented in the YAML after export.
     * @param toEncode String to encode
     * @return Encoded string
     * @since 1.25
     */
    fun encode(toEncode: String?): String? {
        return toEncode?.replace(enclosedBy, escapeEnclosedBy)
    }

    /**
     * Resolve string with potential secrets
     *
     * @param toInterpolate potential variables that need to revealed
     * @return original string with any secrets that could be resolved if secrets could not be
     * resolved they will be defaulted to default value defined by ':-', otherwise default to empty
     * String. Secrets are defined as anything enclosed by '${}'
     */
    fun resolve(toInterpolate: String): String {
        if (StringUtils.isBlank(toInterpolate) || !toInterpolate.contains(enclosedBy)) {
            return toInterpolate
        }
        val buf = TextStringBuilder(toInterpolate)
        substitutor.replaceIn(buf)
        nullSubstitutor.replaceIn(buf)
        return buf.toString()
    }



    companion object {
        private const val enclosedBy = "\${"
        private const val enclosedIn = "}"
        private const val escapedWith = '^'
        private const val escapeEnclosedBy = escapedWith.toString() + enclosedBy
        private val logger = PipelineLogger.getLogger()



//        @Deprecated("use {@link #resolve(String)}} instead.")
//        fun resolve(context: ConfigurationContext, toInterpolate: String?): String {
//            return context.getSecretSourceResolver().resolve(toInterpolate)
//        }
    }
}

internal class UnresolvedLookup private constructor() : StringLookup {
    private val logger = PipelineLogger.getLogger()
    override fun lookup(key: String?): String {
        logger.system(" Configuration import: Found unresolved variable '$key'. Will default to empty string")
        return ""
    }

    companion object {
        val INSTANCE = UnresolvedLookup()
    }
}



internal class SystemPropertyLookup : StringLookup {
    private val logger = PipelineLogger.getLogger()
    override fun lookup(key: String?): String {
        val output = System.getProperty(key)
        if (output == null) {
            logger.error("Configuration import: System Properties did not contain the specified key $key. Will default to empty string.")
            return ""
        }
        return output
    }

    companion object {
        val INSTANCE = SystemPropertyLookup()
    }
}

internal class EnvironmentVariableLookup : StringLookup {
    private val logger = PipelineLogger.getLogger()
    override fun lookup(key: String?): String {
        val output = System.getenv(key)
        if (output == null) {
            logger.error("Configuration import: System environment did not contain the specified key $key. Will default to empty string.")
            return ""
        }
        return output
    }

    companion object {
        val INSTANCE = EnvironmentVariableLookup()
    }
}

internal class FileStringLookup : StringLookup {
    private val logger = PipelineLogger.getLogger()
    override fun lookup(key: String?): String? {
        return try {
            String(Files.readAllBytes(Paths.get(key)), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            logger.error("Configuration import: Error looking up file $key with UTF-8 encoding. Will default to empty string. Error: ${e.message}")
            null
        } catch (e: InvalidPathException) {
            logger.error( "Configuration import: Error looking up file $key with UTF-8 encoding. Will default to empty string. Error: ${e.message}")
            null
        }
    }

    companion object {
        val INSTANCE = FileStringLookup()
    }
}

internal class Base64Lookup : StringLookup {
    override fun lookup(key: String): String {
        return Base64.getEncoder().encodeToString(key.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        val INSTANCE = Base64Lookup()
    }
}

internal class DecodeBase64Lookup : StringLookup {
    override fun lookup(key: String): String {
        return String(Base64.getDecoder().decode(key.toByteArray(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)
    }

    companion object {
        val INSTANCE = DecodeBase64Lookup()
    }
}

internal class FileBase64Lookup : StringLookup {
    private val logger = PipelineLogger.getLogger()
    override fun lookup(key: String?): String? {
        return try {
            val fileContent = Files.readAllBytes(Paths.get(key))
            Base64.getEncoder().encodeToString(fileContent)
        } catch (e: IOException) {
            logger.warn("Configuration import: Error looking up file '%s'. Will default to empty string $key. Error: ${e.message}")
            null
        } catch (e: InvalidPathException) {
            logger.error("Configuration import: Error looking up file '%s'. Will default to empty string $key. Error: ${e.message}")
            null
        }
    }

    companion object {
        val INSTANCE = FileBase64Lookup()
    }
}

internal class JsonLookup private constructor() : StringLookup {
    private val logger = PipelineLogger.getLogger()
    override fun lookup(key: String): String {
//        val components = key.split(":".toRegex(), limit = 2).toTypedArray()
//        val jsonFieldName = components[0]
//        val json = components[1]
//        val root = Json.parseToJsonElement(json).jsonObject
//        val output = root[jsonFieldName]?.jsonPrimitive?.content
//
//        return output ?: "".also {
//            logger.system("Configuration import: JSON did not contain the specified key $jsonFieldName. Will default to empty string.")
//        }
        return ""
    }

    companion object {
        val INSTANCE = JsonLookup()
    }
}


internal class FixedInterpolatorStringLookup(
    stringLookupMap: Map<String, StringLookup>,
    /** The default string lookup.  */
    private var defaultStringLookup: StringLookup?
) :
    StringLookup {
    /** The map of String lookups keyed by prefix.  */
    private val stringLookupMap: MutableMap<String, StringLookup>

    /**
     * Creates a fully customized instance.
     *
     * @param stringLookupMap the map of string lookups.
     * @param defaultStringLookup the default string lookup.
     */
    init {
        this.stringLookupMap = HashMap(stringLookupMap.size)
        for ((key, value) in stringLookupMap) {
            this.stringLookupMap[toKey(key)] = value
        }
        defaultStringLookup = stringLookupMap["env"]
    }

    /**
     * Resolves the specified variable. This implementation will try to extract a variable prefix from the given
     * variable name (the first colon (':') is used as prefix separator). It then passes the name of the variable with
     * the prefix stripped to the lookup object registered for this prefix. If no prefix can be found or if the
     * associated lookup object cannot resolve this variable, the default lookup object will be used.
     *
     * @param var the name of the variable whose value is to be looked up
     * @return The value of this variable or **null** if it cannot be resolved
     */
    override fun lookup(`var`: String): String? {
        if (`var` == null) {
            return null
        }
        val prefixPos = `var`.indexOf(PREFIX_SEPARATOR)
        if (prefixPos >= 0) {
            val prefix = toKey(`var`.substring(0, prefixPos))
            val name = `var`.substring(prefixPos + 1)
            val lookup = stringLookupMap[prefix]
            var value: String? = null
            if (lookup != null) {
                value = lookup.lookup(name)
            }
            if (value != null) {
                return value
            }
        }
        return defaultStringLookup?.lookup(`var`)
    }

    override fun toString(): String {
        return (super.toString() + " [stringLookupMap=" + stringLookupMap + ", defaultStringLookup="
                + defaultStringLookup + "]")
    }

    companion object {
        /** Constant for the prefix separator.  */
        private const val PREFIX_SEPARATOR = ':'
        fun toKey(key: String): String {
            return key.lowercase()
        }
    }
}