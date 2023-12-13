package dev.rubentxu.pipeline.model.repository

import dev.rubentxu.pipeline.model.*
import dev.rubentxu.pipeline.validation.validateAndGet
import java.net.URL
import java.nio.file.Path

enum class SourceCodeType {
    PROJECT,
    PIPELINE_DEFINITION,
    LIBRARY,
    PLUGIN,
}

interface ISourceCodeManager: PipelineComponent {
    val definitions: Map<IDComponent, SourceCodeRepository>
    fun findSourceRepository(id: IDComponent): SourceCodeRepository
}

interface SCMExtension : PipelineComponent
data class SimpleSCMExtension<T>(
    val name: String,
    val value: T,
) : SCMExtension

interface SourceCodeRepository: IPipelineConfig {
    val id: IDComponent
    val name: String
    val description: String?
    val extensions: List<SCMExtension>
    fun retrieve(): SourceCode
}

data class SourceCode(
    val id: IDComponent,
    val name: String,
    val url: URL,
    val type: SourceCodeType,
)

class SourceCodeRepositoryManager(
    override val definitions: Map<IDComponent, SourceCodeRepository>
): ISourceCodeManager {
    override fun findSourceRepository(id: IDComponent): SourceCodeRepository {
        return definitions[id] ?: throw IllegalArgumentException("Source code repository with id '$id' not found")
    }
}

data class GitSourceCodeRepository(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    override val extensions: List<SCMExtension>,
    val branches: List<String>,
    val globalConfigName: String,
    val globalConfigEmail: String,
    val url: URL,
    val credentialsId: String,
) : SourceCodeRepository {
    companion object : PipelineComponentFromMapFactory<GitSourceCodeRepository> {
        override fun create(data: Map<String, Any>): GitSourceCodeRepository {

            val id = IDComponent.create(
                data.validateAndGet("id").isString().throwIfInvalid("id is required in GitScmConfig")
            )

            val name = data.validateAndGet("name")
                .isString()
                .defaultValueIfInvalid(id.toString())

            val description = data.validateAndGet("description")
                .isString()
                .defaultValueIfInvalid("")

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

            val extensions = scmExtension(extensionsMap)

            val url = data.validateAndGet("remote.url")
                .isString()
                .throwIfInvalid("url is required in GitScmConfig")
                .let { URL(it) }
            val credentialsId = data.validateAndGet("remote.credentialsId")
                .isString()
                .throwIfInvalid("credentialsId is required in GitScmConfig")

            return GitSourceCodeRepository(
                id = id,
                branches = branches,
                name = name,
                description = description,
                globalConfigName = globalConfigName,
                globalConfigEmail = globalConfigEmail,
                extensions = extensions,
                url = url,
                credentialsId = credentialsId,
            )
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

    override fun retrieve(): SourceCode {
        TODO("Not yet implemented")
    }
}

data class SvnSourceCodeRepository(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    override val extensions: List<SCMExtension>,
    val branches: List<String>,
    val url: URL,
    val credentialsId: String,
) : SourceCodeRepository {
    companion object : PipelineComponentFromMapFactory<SvnSourceCodeRepository> {
        override fun create(data: Map<String, Any>): SvnSourceCodeRepository {
            val repository = SourceCodeRepositoryBuilder.create(data)
            val id = IDComponent.create(data.validateAndGet("id").isString().throwIfInvalid("id is required in Svn"))

            val branches = data.validateAndGet("branches")
                .isList()
                .defaultValueIfInvalid(emptyList<String>())
            return SvnSourceCodeRepository(id, repository, branches)
        }
    }

    override fun retrieve(): SourceCode {
        TODO("Not yet implemented")
    }
}

data class Mercurial(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    override val extensions: List<SCMExtension>,
    val branches: List<String>,
    val url: URL,
    val credentialsId: String,

    ) : SourceCodeRepository {
    companion object : PipelineComponentFromMapFactory<Mercurial> {
        override fun create(data: Map<String, Any>): Mercurial {
            val sourceRepository = SourceCodeRepositoryBuilder.create(data)
            val id =
                IDComponent.create(data.validateAndGet("id").isString().throwIfInvalid("id is required in Mercurial"))

            val branches = data.validateAndGet("branches")
                .isList()
                .defaultValueIfInvalid(emptyList<String>()) as List<String>


            return Mercurial(id, sourceRepository, branches, emptyList())
        }
    }

    override fun retrieve(): SourceCode {
        TODO("Not yet implemented")
    }
}

class CleanRepository(val clean: Boolean) : SCMExtension {
    companion object : PipelineComponentFromMapFactory<CleanRepository> {
        override fun create(data: Map<String, Any>): CleanRepository {
            return CleanRepository(
                data.validateAndGet("cleanRepository")
                    .isBoolean()
                    .defaultValueIfInvalid(false)
            )
        }
    }
}

data class LocalSourceCodeRepository(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    override val extensions: List<SCMExtension>,
    val branches: List<String>,
    val path: Path,
    val isBareRepo: Boolean,
) : SourceCodeRepository {

    override fun retrieve(): SourceCode {
        TODO("Not yet implemented")
    }
}

class LocalSourceCodeRepositoryFactory(

) : PipelineComponentFromMapFactory<LocalSourceCodeRepository> {
    override fun create(data: Map<String, Any>): LocalSourceCodeRepository {
        val id = IDComponent.create(
            data.validateAndGet("id")
                .isString()
                .throwIfInvalid("id is required in LocalRepository")
        )

        val name = data.validateAndGet("name")
            .isString()
            .defaultValueIfInvalid(id.toString())


        return LocalSourceCodeRepository(
            id = id,
            name = name,
            description = data.validateAndGet("description")
                .isString()
                .defaultValueIfInvalid(""),
            extensions = emptyList<SCMExtension>(),
            branches = data.validateAndGet("branches")
                .isList()
                .defaultValueIfInvalid(emptyList<String>()),
            path = Path.of(
                data.validateAndGet("path")
                    .isString()
                    .throwIfInvalid("path is required in LocalRepository")
            ),
            isBareRepo = data.validateAndGet("isBareRepo")
                .isBoolean()
                .defaultValueIfInvalid(false),

            )
    }
}