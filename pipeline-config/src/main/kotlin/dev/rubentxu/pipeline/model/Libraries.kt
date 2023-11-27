package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.validation.validateAndGet


data class GlobalLibrariesConfig(
    val libraries: List<Library>
): Configuration {
    companion object: MapConfigurationBuilder<GlobalLibrariesConfig> {
        override fun build(data: Map<String, Any>): GlobalLibrariesConfig {
            val librariesMap = data.validateAndGet("libraries")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val libraries: List<Library> = librariesMap.map {
                return@map Library.build(it)
            }

            return GlobalLibrariesConfig(
                libraries = libraries
            )
        }
    }
}

data class Library(
    val name: String,
    val retriever: Retriever
): Configuration {
    companion object: MapConfigurationBuilder<Library> {
        override fun build(data: Map<String, Any>): Library {
            val retrieverMap = data.validateAndGet("retriever")
                .isMap()
                .throwIfInvalid("retriever is required in Library") as Map<String, Any>

            return Library(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Library"),
                retriever = Retriever.build(retrieverMap)
            )
        }
    }
}

sealed class Retriever: Configuration{
    companion object: MapConfigurationBuilder<Retriever> {
        override fun build(data: Map<String, Any>): Retriever {
            return when (data?.keys?.first()) {
                "scm" -> Scm.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "local" -> Local.build(data.get(data?.keys?.first()) as Map<String, Any>)
                else -> {
                    throw IllegalArgumentException("Invalid retriever type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}
data class Scm(
    val git: GitRetriever
) : Retriever() {
    companion object: MapConfigurationBuilder<Scm> {
        override fun build(data: Map<String, Any>): Scm {
            val gitMap = data.validateAndGet("git")
                .isMap()
                .throwIfInvalid("git is required in Scm") as Map<String, Any>

            return Scm(
                git = GitRetriever.build(gitMap)
            )
        }
    }

}

data class Local(
    val path: String
) : Retriever() {
    companion object: MapConfigurationBuilder<Local> {
        override fun build(data: Map<String, Any>): Local {
            return Local(
                path = data.validateAndGet("path").isString().throwIfInvalid("path is required in Local")
            )
        }
    }

}


data class GitRetriever(
    val remote: String,
    val credentialsId: String
) : Configuration {
    companion object: MapConfigurationBuilder<GitRetriever> {
        override fun build(data: Map<String, Any>): GitRetriever {
            return GitRetriever(
                remote = data.validateAndGet("remote").isString().throwIfInvalid("remote is required in GitRetriever"),
                credentialsId = data.validateAndGet("credentialsId").isString().throwIfInvalid("credentialsId is required in GitRetriever")
            )
        }
    }
}