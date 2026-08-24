package dev.rubentxu.pipeline.v2.sdk

/**
 * Per-step LSP/docs metadata schema (E3-08). Mirrors the JSON resource at
 * META-INF/pipeline/step-metadata/{stepId}.json.
 *
 * Schema contract:
 *   { "schema": "pipeline.dev/lsp/v1",
 *     "stepId": "...",
 *     "name": "...",
 *     "parameters": [...],
 *     "location": "CONTROLLER|WORKER|AGENT",
 *     "replayPolicy": "PURE|MEMOIZED|REUSE_RESULT|RERUN|NEVER",
 *     "failureKindBridge": "<canonical Jenkins failure kind>",
 *     "jenkinsSurface": "<step>|<plugin>|F<n>" | "" }
 *
 * Serialization uses JsonEventLog.jsonString() escape rules at
 * JsonEventLog.kt:208-221 — no third-party JSON library (F-ARCH-001).
 */
data class LspMetadata(
    val schema: String,
    val stepId: String,
    val name: String,
    val parameters: List<LspParameter>,
    val location: String,
    val replayPolicy: String,
    val failureKindBridge: String,
    val jenkinsSurface: String,
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append(jsonField("schema", schema))
        sb.append(",")
        sb.append(jsonField("stepId", stepId))
        sb.append(",")
        sb.append(jsonField("name", name))
        sb.append(",")
        sb.append("\"parameters\":[")
        parameters.forEachIndexed { idx, p ->
            if (idx > 0) sb.append(",")
            sb.append("{")
            sb.append(jsonField("name", p.name))
            sb.append(",")
            sb.append(jsonField("type", p.type))
            sb.append(",")
            sb.append(jsonField("required", p.required.toString()))
            sb.append(",")
            sb.append(jsonField("index", p.index.toString()))
            sb.append("}")
        }
        sb.append("],")
        sb.append(jsonField("location", location))
        sb.append(",")
        sb.append(jsonField("replayPolicy", replayPolicy))
        sb.append(",")
        sb.append(jsonField("failureKindBridge", failureKindBridge))
        sb.append(",")
        sb.append(jsonField("jenkinsSurface", jenkinsSurface))
        sb.append("}")
        return sb.toString()
    }

    private fun jsonField(key: String, value: String): String {
        val escaped = jsonString(value)
        return "\"$key\":$escaped"
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return "\"${sb}\""
    }

    companion object {
        const val SCHEMA_VERSION = "pipeline.dev/lsp/v1"

        fun fromJson(s: String): LspMetadata? {
            try {
                val result = parseLspMetadata(s)
                // Validate required fields - stepId is the most critical
                if (result.stepId.isEmpty()) {
                    return null
                }
                return result
            } catch (e: Exception) {
                return null
            }
        }

        private fun parseLspMetadata(s: String): LspMetadata {
            // Simple JSON parser for LSP metadata
            val map = mutableMapOf<String, Any?>()
            val params = mutableListOf<LspParameter>()

            // Remove surrounding braces
            var content = s.trim()
            if (content.startsWith("{")) content = content.substring(1)
            if (content.endsWith("}")) content = content.substring(0, content.length - 1)

            var i = 0
            while (i < content.length) {
                // Find key
                while (i < content.length && content[i] == ' ') i++
                if (i >= content.length) break

                if (content[i] != '"') {
                    i++
                    continue
                }
                i++
                val keyEnd = content.indexOf('"', i)
                if (keyEnd < 0) break
                val key = content.substring(i, keyEnd)
                i = keyEnd + 1

                // Find colon
                while (i < content.length && content[i] != ':') i++
                if (i >= content.length) break
                i++

                // Find value
                while (i < content.length && content[i] == ' ') i++

                when {
                    content[i] == '"' -> {
                        val valueEnd = findStringEnd(content, i + 1)
                        val value = content.substring(i + 1, valueEnd)
                        map[key] = value
                        i = valueEnd + 1
                    }
                    content[i] == '[' -> {
                        val arrayEnd = findArrayEnd(content, i)
                        val arrayContent = content.substring(i + 1, arrayEnd)
                        if (key == "parameters") {
                            parseParameters(arrayContent, params)
                        }
                        i = arrayEnd + 1
                    }
                    else -> {
                        val valueEnd = findValueEnd(content, i)
                        val value = content.substring(i, valueEnd).trim()
                        map[key] = value
                        i = valueEnd
                    }
                }

                // Skip comma
                while (i < content.length && (content[i] == ' ' || content[i] == ',')) i++
            }

            return LspMetadata(
                schema = map["schema"] as? String ?: SCHEMA_VERSION,
                stepId = map["stepId"] as? String ?: "",
                name = map["name"] as? String ?: "",
                parameters = params,
                location = map["location"] as? String ?: "",
                replayPolicy = map["replayPolicy"] as? String ?: "",
                failureKindBridge = map["failureKindBridge"] as? String ?: "",
                jenkinsSurface = map["jenkinsSurface"] as? String ?: "",
            )
        }

        private fun findStringEnd(s: String, start: Int): Int {
            var i = start
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    i += 2
                } else if (s[i] == '"') {
                    return i
                } else {
                    i++
                }
            }
            return s.length
        }

        private fun findArrayEnd(s: String, start: Int): Int {
            var depth = 1
            var i = start + 1
            while (i < s.length && depth > 0) {
                when (s[i]) {
                    '[' -> depth++
                    ']' -> depth--
                }
                i++
            }
            return i - 1
        }

        private fun findValueEnd(s: String, start: Int): Int {
            var i = start
            while (i < s.length && s[i] != ',' && s[i] != '}') i++
            return i
        }

        private fun parseParameters(content: String, params: MutableList<LspParameter>) {
            var i = 0
            while (i < content.length) {
                while (i < content.length && (content[i] == ' ' || content[i] == ',')) i++
                if (i >= content.length) break
                if (content[i] != '{') {
                    i++
                    continue
                }
                val objEnd = findObjectEnd(content, i)
                val objContent = content.substring(i + 1, objEnd)
                params.add(parseParameter(objContent))
                i = objEnd + 1
            }
        }

        private fun findObjectEnd(s: String, start: Int): Int {
            var depth = 1
            var i = start + 1
            while (i < s.length && depth > 0) {
                when (s[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            return i - 1
        }

        private fun parseParameter(content: String): LspParameter {
            var name = ""
            var type = ""
            var required = false
            var index = 0

            var i = 0
            while (i < content.length) {
                while (i < content.length && (content[i] == ' ' || content[i] == ',')) i++
                if (i >= content.length) break

                if (content[i] != '"') {
                    i++
                    continue
                }
                i++
                val keyEnd = content.indexOf('"', i)
                if (keyEnd < 0) break
                val key = content.substring(i, keyEnd)
                i = keyEnd + 1

                while (i < content.length && content[i] != ':') i++
                if (i >= content.length) break
                i++

                while (i < content.length && content[i] == ' ') i++

                when {
                    content[i] == '"' -> {
                        val valueEnd = findStringEnd(content, i + 1)
                        val value = content.substring(i + 1, valueEnd)
                        when (key) {
                            "name" -> name = value
                            "type" -> type = value
                        }
                        i = valueEnd + 1
                    }
                    else -> {
                        val valueEnd = findValueEnd(content, i)
                        val value = content.substring(i, valueEnd).trim()
                        when (key) {
                            "required" -> required = value == "true"
                            "index" -> index = value.toIntOrNull() ?: 0
                        }
                        i = valueEnd
                    }
                }
            }

            return LspParameter(name, type, required, index)
        }
    }
}

data class LspParameter(
    val name: String,
    val type: String,
    val required: Boolean,
    val index: Int,
)
