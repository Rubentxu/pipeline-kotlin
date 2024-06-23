package dev.rubentxu.pipeline.core.models.pipeline

import dev.rubentxu.pipeline.core.models.interfaces.PipelineModel


data class CacheDescriptor(
    val baseDir: String,
    val cacheFolders: Set<String>,
    val exportEnvVar: String,
    val forceClearCache: Boolean
) : PipelineModel {

    override fun toMap(): Map<String, Any> {
        return mapOf(
            "baseDir" to baseDir,
            "cacheFolders" to cacheFolders,
            "exportEnvVar" to exportEnvVar,
            "forceClearCache" to forceClearCache
        )
    }
}