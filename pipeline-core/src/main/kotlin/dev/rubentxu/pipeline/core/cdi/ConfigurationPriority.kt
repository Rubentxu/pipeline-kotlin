package dev.rubentxu.pipeline.core.cdi

enum class ConfigurationPriority(val value: Int) {
    HIGHEST(1),
    HIGH(2),
    MEDIUM(5),
    LOW(10),
    LOWEST(15)
}