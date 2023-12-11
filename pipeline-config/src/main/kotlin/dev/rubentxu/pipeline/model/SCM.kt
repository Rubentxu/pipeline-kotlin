package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.config.Configuration
import dev.rubentxu.pipeline.model.config.MapConfigurationBuilder
import dev.rubentxu.pipeline.validation.validateAndGet

data class ScmConfig(
    val definitions: List<ScmDefinition>,
) : Configuration {
    companion object : MapConfigurationBuilder<ScmConfig> {
        override fun build(data: Map<String, Any>): ScmConfig {
            val scmListMap = data.validateAndGet("scm")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>())
            val scmList = scmListMap.map { ScmDefinition.build(it) }
            return ScmConfig(scmList)
        }
    }
}


interface ScmDefinition : Configuration {
    val sourceRepository: SourceRepository
    val branches: List<String>

    companion object : MapConfigurationBuilder<ScmDefinition> {
        override fun build(data: Map<String, Any>): ScmDefinition {
            return when (data.keys.first()) {
                "git" -> GitScmConfig.build(
                    data.validateAndGet("git")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )

                "svn" -> Svn.build(
                    data.validateAndGet("svn")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )

                "mercurial" -> Mercurial.build(
                    data.validateAndGet("mercurial")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )

                else -> throw IllegalArgumentException("Invalid SCM type for '${data.keys.first()}'")
            }
        }
    }
}

data class GitScmConfig(
    override val sourceRepository: SourceRepository,
    override val branches: List<String>,
    val globalConfigName: String,
    val globalConfigEmail: String,
    val extensions: List<SCMExtension>,
) : ScmDefinition {
    companion object : MapConfigurationBuilder<GitScmConfig> {
        override fun build(data: Map<String, Any>): GitScmConfig {

            val sourceRepository = SourceRepository.build(data)

            val branches = data.validateAndGet("branches")
                .dependsAnyOn(data, "remote", "local")
                .isList()
                .defaultValueIfInvalid(emptyList<String>())

            val globalConfigName = data.validateAndGet("globalConfigName")
                .isString()
                .defaultValueIfInvalid("")

            val globalConfigEmail = data.validateAndGet("globalConfigEmail")
                .isString()
                .defaultValueIfInvalid("")

            val extensionsMap = data.validateAndGet("extensions")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>())

            val extensions =  scmExtension(extensionsMap)

            return GitScmConfig(sourceRepository, branches, globalConfigName, globalConfigEmail, extensions)
        }

        private fun scmExtension(extensionsMap: Map<String, Any>): List<SCMExtension> {
            return extensionsMap.map {
                when (it.key) {
                    "sparseCheckoutPaths" -> SimpleSCMExtension(
                        "sparseCheckoutPaths",
                        extensionsMap.validateAndGet("sparseCheckoutPaths")
                            .isList()
                            .defaultValueIfInvalid(emptyList<String>())
                    )

                    "cloneOptions" -> SimpleSCMExtension(
                        "cloneOptions",
                        extensionsMap.validateAndGet("cloneOptions")
                            .isString()
                            .defaultValueIfInvalid("")
                    )

                    "relativeTargetDirectory" -> SimpleSCMExtension(
                        "relativeTargetDirectory",
                        extensionsMap.validateAndGet("relativeTargetDirectory")
                            .isString()
                            .defaultValueIfInvalid("")
                    )

                    "shallowClone" -> SimpleSCMExtension(
                        "shallowClone",
                        extensionsMap.validateAndGet("shallowClone")
                            .isBoolean()
                            .defaultValueIfInvalid(false)
                    )

                    "timeout" -> SimpleSCMExtension(
                        "timeout",
                        extensionsMap.validateAndGet("timeout")
                            .isNumber()
                            .defaultValueIfInvalid(10)
                    )

                    "lfs" -> SimpleSCMExtension(
                        "lfs",
                        extensionsMap.validateAndGet("lfs")
                            .isBoolean()
                            .defaultValueIfInvalid(false)
                    )

                    "submodules" -> SimpleSCMExtension(
                        "submodules",
                        extensionsMap.validateAndGet("submodules")
                            .isBoolean()
                            .defaultValueIfInvalid(false)
                    )

                    else -> throw IllegalArgumentException("Invalid SCM extension type for '${extensionsMap.keys.first()}'")
                }

            }
        }

    }
}

interface SCMExtension : Configuration
data class SimpleSCMExtension<T>(
    val name: String,
    val value: T,
) : SCMExtension


data class Svn(
    override val sourceRepository: SourceRepository,
    override val branches: List<String>,
) : ScmDefinition {
    companion object : MapConfigurationBuilder<Svn> {
        override fun build(data: Map<String, Any>): Svn {
            val repository = SourceRepository.build(data)

            val branches = data.validateAndGet("branches")
                .isList()
                .defaultValueIfInvalid(emptyList<String>())
            return Svn(repository, branches)
        }
    }
}

data class Mercurial(
    override val sourceRepository: SourceRepository,
    override val branches: List<String>,
    val extensions: List<SCMExtension>,
) : ScmDefinition {
    companion object : MapConfigurationBuilder<Mercurial> {
        override fun build(data: Map<String, Any>): Mercurial {
            val sourceRepository = SourceRepository.build(data)

            val branches = data.validateAndGet("branches")
                .isList()
                .defaultValueIfInvalid(emptyList<String>()) as List<String>


            return Mercurial(sourceRepository, branches, emptyList())
        }


    }


}

class CleanRepository(val clean: Boolean) : SCMExtension {
    companion object : MapConfigurationBuilder<CleanRepository> {
        override fun build(data: Map<String, Any>): CleanRepository {
            return CleanRepository(
                data.validateAndGet("cleanRepository")
                    .isBoolean()
                    .defaultValueIfInvalid(false)
            )
        }
    }
}

interface SourceRepository : Configuration {
    companion object : MapConfigurationBuilder<SourceRepository> {
        override fun build(data: Map<String, Any>): SourceRepository {
            return if (data.containsKey("remote")) {
               RemoteRepository.build(data.get("remote") as Map<String, Any>)
            } else if (data.containsKey("local")) {
               LocalRepository.build(data.get("local") as Map<String, Any>)
            } else {
                throw IllegalArgumentException("Invalid SCM type for '${data.keys.first()}'")
            }

        }
    }
}

data class LocalRepository(
    val path: String,
    val isBareRepo: Boolean,
) : SourceRepository {
    companion object : MapConfigurationBuilder<LocalRepository> {
        override fun build(data: Map<String, Any>): LocalRepository {
            return LocalRepository(
                data.validateAndGet("path")
                    .isString()
                    .throwIfInvalid("path is required in LocalRepository"),
                data.validateAndGet("isBareRepo")
                    .isBoolean()
                    .defaultValueIfInvalid(false)
            )
        }
    }
}

data class RemoteRepository(
    val url: String,
    val credentialsId: String,
) : SourceRepository {
    companion object : MapConfigurationBuilder<RemoteRepository> {
        override fun build(data: Map<String, Any>): RemoteRepository {
            return RemoteRepository(
                data.validateAndGet("url")
                    .isString()
                    .notEmpty()
                    .throwIfInvalid("url is required in RemoteRepository"),
                data.validateAndGet("credentialsId")
                    .isString()
                    .defaultValueIfInvalid("")
            )
        }
    }
}

