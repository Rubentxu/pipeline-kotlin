package dev.rubentxu.pipeline.v2.domain.step.http

/**
 * A range of acceptable status codes (RP6-C / WU-093).
 *
 * Jenkins takes this as the string `"100:399,404"` and parses it **after the request
 * has already been sent** (`HttpRequest.java:552-589`), so a typo in a pipeline
 * produces an `IllegalArgumentException` raised against a request that reached the
 * world. Here the shape is validated when the intent is resolved — a pure function
 * applied at decode time — so a bad range is a *declaration* error and never a
 * surprise that arrives after the fact.
 */
sealed interface StatusRange {
    fun contains(status: Int): Boolean

    data class Single(val code: Int) : StatusRange {
        override fun contains(status: Int): Boolean = status == code
    }

    data class Span(val from: Int, val to: Int) : StatusRange {
        init {
            require(from <= to) { "a status span must not run backwards: $from..$to" }
            require(from in 100..599) { "a status span must start within 100..599, got $from" }
            require(to in 100..599) { "a status span must end within 100..599, got $to" }
        }

        override fun contains(status: Int): Boolean = status in from..to
    }

    companion object {
        /**
         * Parses the Jenkins spelling (`"100:399,404"`) into typed ranges, so a
         * pipeline written for Jenkins runs here unedited.
         *
         * Total: anything unparseable returns `null` and the caller turns that into a
         * typed rejection. It never throws and never guesses: an unrecognised token
         * must not silently widen or narrow what the author wrote.
         */
        fun parse(spec: String): List<StatusRange>? {
            if (spec.isBlank()) return null
            val ranges = mutableListOf<StatusRange>()
            for (token in spec.split(',')) {
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return null
                val parts = trimmed.split(':')
                val range = when (parts.size) {
                    1 -> trimmed.toIntOrNull()?.let { StatusRange.Single(it) }
                    2 -> {
                        val from = parts[0].trim().toIntOrNull()
                        val to = parts[1].trim().toIntOrNull()
                        if (from == null || to == null || from > to) null
                        else StatusRange.Span(from, to)
                    }
                    else -> null
                } ?: return null
                if (range is StatusRange.Single && range.code !in 100..599) return null
                ranges += range
            }
            return ranges.ifEmpty { null }
        }

        /** The Jenkins default: any status below 400 is success. */
        fun jenkinsDefault(): List<StatusRange> = listOf(Span(100, 399))
    }
}
