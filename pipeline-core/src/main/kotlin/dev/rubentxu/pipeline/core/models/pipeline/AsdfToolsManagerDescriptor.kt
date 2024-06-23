package dev.rubentxu.pipeline.core.models.pipeline

import dev.rubentxu.pipeline.core.models.interfaces.PipelineModel


data class AsdfToolsManagerDescriptor(
    val defaultToolVersionsFileDir: String,
    val defaultDataDir: String,
    val forceClearCache: Boolean = false,
    val proxySettings: ProxySettings,
    val plugins: Map<String, String> = emptyMap(),
    val tools: Map<String, String> = emptyMap()
) : PipelineModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "defaultToolVersionsFileDir" to defaultToolVersionsFileDir,
            "defaultDataDir" to defaultDataDir,
            "forceClearCache" to forceClearCache,
            "proxySettings" to proxySettings.toMap(),
            "plugins" to plugins,
            "tools" to tools
        )
    }
}
