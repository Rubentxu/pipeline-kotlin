package dev.rubentxu.pipeline.model.logger

class LogFormat private constructor(
    val color: String,
    val level: LogLevel,
    val text: String,
    val style: String,
    val reset: String,
) {


    data class Builder(
        var color: String = "",
        var level: LogLevel = LogLevel.INFO,
        var text: String,
        var style: String = "",
        var reset: String = RESET,
    ) {

        fun color(color: String) = apply { this.color = color }
        fun level(level: LogLevel) = apply { this.level = level }

        fun text(text: String) = apply { this.text = text }

        fun style(style: String) = apply { this.style = style }

        fun reset(reset: String) = apply { this.reset = reset }

        fun build() = LogFormat(color, level, text, style, reset)


    }

    companion object {
        const val RESET = "\u001B[0m"
    }

}

