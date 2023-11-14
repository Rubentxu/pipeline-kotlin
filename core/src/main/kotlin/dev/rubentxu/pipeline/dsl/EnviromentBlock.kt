package dev.rubentxu.pipeline.dsl

import java.util.concurrent.ConcurrentHashMap

class EnvironmentBlock {
    val map = mutableMapOf<String, String>()

    operator fun String.plusAssign(value: String) {
        map[this] = value
    }
}
