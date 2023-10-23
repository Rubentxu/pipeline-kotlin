package dev.rubentxu.pipeline.logger

class PipelineLogger(private var logLevel: String) : IPipelineLogger {
    val logs = mutableListOf<String>()

    private val RESET = "\u001B[0m"
    private val RED = "\u001B[31m"
    private val YELLOW = "\u001B[33m"
    private val GREEN = "\u001B[32m"
    private val MAGENTA = "\u001B[35m"
    private val CYAN = "\u001B[36m"
    private val BOLD = "\u001B[1m"
    private val ITALIC = "\u001B[3m"

    private val LEVEL_NUMBERS = mapOf(
        "FATAL" to 100,
        "ERROR" to 200,
        "WARN" to 300,
        "INFO" to 400,
        "DEBUG" to 500,
        "SYSTEM" to 600
    )

    init {
        setLogLevel(logLevel)
    }

    private fun setLogLevel(level: String) {
        logLevel = level
    }

    private fun log(level: String, message: String) {
        val formatOpts = mutableMapOf(
            "color" to "",
            "level" to level,
            "text" to message,
            "style" to "",
            "reset" to RESET
        )

        if (!LEVEL_NUMBERS.containsKey(level)) return
        if (LEVEL_NUMBERS[level]!! <= LEVEL_NUMBERS[logLevel]!!) {
            when (level) {
                "FATAL", "ERROR" -> {
                    formatOpts["color"] = RED
                    formatOpts["style"] = BOLD
                }
                "WARN" -> {
                    formatOpts["color"] = YELLOW
                    formatOpts["style"] = BOLD
                }
                "INFO" -> {
                    formatOpts["color"] = GREEN
                }
                "DEBUG" -> {
                    formatOpts["color"] = MAGENTA
                    formatOpts["style"] = ITALIC
                }
                "SYSTEM" -> {
                    formatOpts["color"] = CYAN
                    formatOpts["style"] = ITALIC
                }
            }
            write(formatOpts)
        }
    }

    private fun write(formatOpts: Map<String, String>) {
        val msg = formatMessage(formatOpts)
        logs.add(msg)
        println(msg)
    }

    private fun formatMessage(options: Map<String, String>): String {
        return "${options["color"]}${options["style"]}[${options["level"]}] ${options["text"]}${options["reset"]}"
    }

    override fun info(message: String) {
        log("INFO", message)
    }

    override fun warn(message: String) {
        log("WARN", message)
    }

    override fun debug(message: String) {
        log("DEBUG", message)
    }

    override fun error(message: String) {
        log("ERROR", message)
    }

    override fun fatal(message: String) {
        log("FATAL", message)
    }

    override fun system(message: String) {
        log("SYSTEM", message)
    }

    fun whenDebug(body: () -> Unit) {
        if (logLevel == "DEBUG") {
            body()
        }
    }

    fun <T> prettyPrint(levelLog: String, obj: T) {
        log(levelLog, prettyPrintExtend(obj))
    }

    private fun <T> prettyPrintExtend(obj: T, level: Int = 0, sb: StringBuilder = StringBuilder()): String {
        fun indent(lev: Int) = sb.append("  ".repeat(lev))

        when (obj) {
            is Map<*, *> -> {
                sb.append("{\n")
                obj.forEach { (name, value) ->
                    indent(level + 1).append(name)
                    if (value is Map<*, *>) {
                        sb.append(" ")
                    } else {
                        sb.append(" = ")
                    }
                    sb.append(prettyPrintExtend(value, level + 1)).append("\n")
                }
                indent(level).append("}")
            }
            is List<*> -> {
                sb.append("[\n")
                obj.forEachIndexed { index, value ->
                    indent(level + 1)
                    sb.append(prettyPrintExtend(value, level + 1))
                    if (index != obj.size - 1) {
                        sb.append(",")
                    }
                    sb.append("\n")
                }
                indent(level).append("]")
            }
            is String -> sb.append('"').append(obj).append('"')
            else -> sb.append(obj)
        }
        return sb.toString()
    }

    fun echoBanner(level: String, messages: List<String>) {
        log(level, createBanner(messages))
    }

    fun errorBanner(msgs: List<String>) {
        error(createBanner(msgs))
    }

    private fun createBanner(msgs: List<String>): String {
        return """
        |===========================================
        |${msgFlatten(mutableListOf(), msgs.filter { it.isNotEmpty() }).joinToString("\n")}
        |===========================================
        """.trimMargin()
    }

    private fun msgFlatten(list: MutableList<String>, msgs: Any): List<String> {
        when (msgs) {
            is String -> list.add(msgs)
            is List<*> -> msgs.forEach { msg -> msgFlatten(list, msg ?: "") }
        }
        return list
    }
}