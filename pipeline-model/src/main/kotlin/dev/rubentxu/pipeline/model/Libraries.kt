package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.validation.validateAndGet


data class GlobalLibrariesConfig(
    val libraries: List<Library>,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<GlobalLibrariesConfig> {
        override fun create(data: Map<String, Any>): GlobalLibrariesConfig {
            val librariesMap = data.validateAndGet("pipeline.globalLibraries.libraries")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val libraries: List<Library> = librariesMap.map {
                return@map Library.create(it)
            }

            return GlobalLibrariesConfig(
                libraries = libraries
            )
        }
    }
}

data class Library(
    val name: String,
    val retriever: Retriever,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<Library> {
        override fun create(data: Map<String, Any>): Library {
            val retrieverMap = data.validateAndGet("retriever")
                .isMap()
                .throwIfInvalid("retriever is required in Library") as Map<String, Any>

            return Library(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Library"),
                retriever = Retriever.create(retrieverMap)
            )
        }
    }
}

sealed class Retriever : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<Retriever> {
        override fun create(data: Map<String, Any>): Retriever {
            return when (data?.keys?.first()) {
                "gitSCM" -> GitSCMRetriever.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "local" -> Local.create(data.get(data?.keys?.first()) as Map<String, Any>)
                else -> {
                    throw IllegalArgumentException("Invalid retriever type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}


data class Local(
    val path: String,
) : Retriever() {
    companion object : PipelineComponentFromMapFactory<Local> {
        override fun create(data: Map<String, Any>): Local {
            return Local(
                path = data.validateAndGet("path").isString().throwIfInvalid("path is required in Local")
            )
        }
    }

}


data class GitSCMRetriever(
    val remote: String,
    val credentialsId: String,
) : Retriever() {
    companion object : PipelineComponentFromMapFactory<GitSCMRetriever> {
        override fun create(data: Map<String, Any>): GitSCMRetriever {
            return GitSCMRetriever(
                remote = data.validateAndGet("remote").isString()
                    .throwIfInvalid("Value for remote is required in path 'pipeline.globalLibraries.libraries[n].retriever.gitSCM.remote'"),
                credentialsId = data.validateAndGet("credentialsId").isString()
                    .throwIfInvalid("Value for credentialsId is required in path 'pipeline.globalLibraries.libraries[n].retriever.gitSCM.credentialsId'")
            )
        }
    }
}