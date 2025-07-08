package dev.rubentxu.pipeline.logger

/**
 * Represents the format for a log message, including color, level, text, style, and reset code.
 *
 * Use [Builder] to construct instances.
 *
 * @property color The ANSI color code for the log message.
 * @property level The log level.
 * @property text The log message text.
 * @property style The ANSI style code for the log message.
 * @property reset The ANSI reset code.
 */
class LogFormat private constructor(
    val color: String,
    val level: LogLevel,
    val text: String,
    val style: String,
    val reset: String,
) {

    /**
     * Builder for [LogFormat].
     *
     * @property color The ANSI color code.
     * @property level The log level.
     * @property text The log message text.
     * @property style The ANSI style code.
     * @property reset The ANSI reset code.
     */
    data class Builder(
        var color: String = "",
        var level: LogLevel = LogLevel.INFO,
        var text: String,
        var style: String = "",
        var reset: String = RESET,
    ) {

        /**
         * Sets the color for the log format.
         */
        fun color(color: String) = apply { this.color = color }

        /**
         * Sets the log level.
         */
        fun level(level: LogLevel) = apply { this.level = level }

        /**
         * Sets the log message text.
         */
        fun text(text: String) = apply { this.text = text }

        /**
         * Sets the style for the log format.
         */
        fun style(style: String) = apply { this.style = style }

        /**
         * Sets the reset code for the log format.
         */
        fun reset(reset: String) = apply { this.reset = reset }

        /**
         * Builds the [LogFormat] instance.
         */
        fun build() = LogFormat(color, level, text, style, reset)
    }

    companion object {
        /** ANSI reset code. */
        const val RESET = "\u001B[0m"
    }
}
