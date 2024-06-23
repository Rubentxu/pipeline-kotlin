package dev.rubentxu.pipeline.core.models.pipeline

import dev.rubentxu.pipeline.core.models.interfaces.PipelineModel

data class ProxySettings(
    val httpProxy: String = "",
    val httpsProxy: String = "",
    val noProxy: String = ""
) : PipelineModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "httpProxy" to httpProxy,
            "httpsProxy" to httpsProxy,
            "noProxy" to noProxy
        )
    }
}