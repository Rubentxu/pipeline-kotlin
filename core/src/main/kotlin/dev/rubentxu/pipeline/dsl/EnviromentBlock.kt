package dev.rubentxu.pipeline.dsl

class EnvironmentBlock {
    val map = mutableMapOf<String, String>()

    operator fun String.plusAssign(value: String) {
        map[this] = value
    }
}
