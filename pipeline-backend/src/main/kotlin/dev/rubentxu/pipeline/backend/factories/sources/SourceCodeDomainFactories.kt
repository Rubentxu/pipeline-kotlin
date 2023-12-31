package dev.rubentxu.pipeline.backend.factories.sources

import dev.rubentxu.pipeline.backend.sources.CleanRepository
import dev.rubentxu.pipeline.backend.sources.GitSourceCodeRepository
import dev.rubentxu.pipeline.backend.sources.LocalSourceCodeRepository
import dev.rubentxu.pipeline.backend.sources.Mercurial
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineCollection
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.repository.*
import dev.rubentxu.pipeline.model.validations.validateAndGet
import java.net.URL
import java.nio.file.Path


class PluginsDefinitionSourceFactory : PipelineDomain {

    companion object : PipelineDomainFactory<PipelineCollection<PluginSourceCodeConfig>> {
        override val rootPath: String = "pipeline.plugins"
        override val instanceName: String = "PluginSourceCodeConfig"

        override suspend fun create(data: Map<String, Any>): PipelineCollection<PluginSourceCodeConfig> {

            val plugins = getRootListObject(data)

            return plugins.mapIndexed { index, plugin ->
                val name = plugin.validateAndGet("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("[$index].name"))

                val description = plugin.validateAndGet("description")
                    .isString()
                    .defaultValueIfInvalid("")

                val fromFile = plugin.validateAndGet("fromFile")
                    .isString()
                    .defaultValueIfInvalid("")

                val fromLocalDirectory = plugin.validateAndGet("fromLocalDirectory")
                    .isString()
                    .defaultValueIfInvalid("")

                val fromFilePath = if (fromFile.isNotEmpty()) Path.of(fromFile) else null
                val fromLocalDirectoryPath = if (fromLocalDirectory.isNotEmpty()) Path.of(fromLocalDirectory) else null

                val module = plugin.validateAndGet("module")
                    .isString()
                    .throwIfInvalid(getErrorMessage("[$index].module"))

                PluginSourceCodeConfig(
                    name = name,
                    description = description,
                    module = module,
                    fromFile = fromFilePath,
                    fromLocalDirectory = fromLocalDirectoryPath,

                    )
            }.let { PipelineCollection(it) }

        }
    }

}


class PipelineFileSourceCodeFactory : PipelineDomain {

    companion object : PipelineDomainFactory<SourceCodeConfig> {
        override val rootPath: String = "pipeline.pipelineSourceCode"
        override val instanceName: String = "PipelineFileSource"

        override suspend fun create(data: Map<String, Any>): SourceCodeConfig {
            val pipelineSourceCode = getRootMapObject(data)

            val scmReferenceId = IDComponent.create(
                pipelineSourceCode.validateAndGet("repository.referenceId")
                    .isString()
                    .throwIfInvalid(getErrorMessage("repository.referenceId"))
            )

            val relativeScriptPath = pipelineSourceCode.validateAndGet("scriptPath")
                .isString()
                .defaultValueIfInvalid("")

            return SourceCodeConfig(
                repositoryId = scmReferenceId,
                relativePath = Path.of(relativeScriptPath),
                sourceCodeType = SourceCodeType.PIPELINE_DEFINITION
            )
        }
    }

}

class ProjectSourceCodeFactory : PipelineDomain {

    companion object : PipelineDomainFactory<SourceCodeConfig> {
        override val rootPath: String = "pipeline.projectSourceCode"
        override val instanceName: String = "ProjectSourceCode"

        override suspend fun create(data: Map<String, Any>): SourceCodeConfig {

            val projectSourceCode = getRootMapObject(data)

            val scmReferenceId = IDComponent.create(
                projectSourceCode.validateAndGet("repository.referenceId")
                    .isString()
                    .throwIfInvalid(getErrorMessage("repository.referenceId"))
            )

            return SourceCodeConfig(
                repositoryId = scmReferenceId,
                relativePath = null,
                sourceCodeType = SourceCodeType.PROJECT
            )
        }
    }

}


class MercurialSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<Mercurial> {
        override val rootPath: String = "repositories.mercurial"
        override val instanceName: String = "Mercurial"

        override suspend fun create(data: Map<String, Any>): Mercurial {
            val repositoryMercurial = getRootMapObject(data)

            val id =
                IDComponent.create(
                    repositoryMercurial.validateAndGet("id")
                        .isString()
                        .throwIfInvalid(getErrorMessage("id"))
                )

            val name = repositoryMercurial.validateAndGet("name")
                .isString()
                .defaultValueIfInvalid(id.toString())

            val description = repositoryMercurial.validateAndGet("description")
                .isString()
                .defaultValueIfInvalid("")

            val branches = repositoryMercurial.validateAndGet("branches")
                .isList()
                .defaultValueIfInvalid(emptyList<String>())

            val url = repositoryMercurial.validateAndGet("remote.url")
                .isString()
                .throwIfInvalid(getErrorMessage("remote.url"))
                .let { URL(it) }

            val credentialsId = repositoryMercurial.validateAndGet("remote.credentialsId")
                .isString()
                .defaultValueIfInvalid("")


            return Mercurial(
                id = id,
                name = name,
                description = description,
                extensions = emptyList(),
                branches = branches,
                url = url,
                credentialsId = credentialsId,
            )
        }
    }

}


class LocalGitSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<LocalSourceCodeRepository> {
        override val rootPath: String = "repositories.local"
        override val instanceName: String = "LocalSourceCodeRepository"
        override suspend fun create(data: Map<String, Any>): LocalSourceCodeRepository {
            val localRepository = getRootMapObject(data)

            val id = IDComponent.create(
                localRepository.validateAndGet("id")
                    .isString()
                    .throwIfInvalid(getErrorMessage("id"))
            )

            val name = localRepository.validateAndGet("name")
                .isString()
                .defaultValueIfInvalid(id.toString())

            val description = localRepository.validateAndGet("description")
                .isString()
                .defaultValueIfInvalid("")

            val path = Path.of(
                localRepository.validateAndGet("path")
                    .isString()
                    .throwIfInvalid(getErrorMessage("path"))
            )


            return LocalSourceCodeRepository(
                id = id,
                name = name,
                description = description,
                path = Path.of(
                    data.validateAndGet("git.local.path")
                        .isString()
                        .throwIfInvalid("path is required in LocalRepository")
                ),

                )
        }
    }
}


class GitSourceCodeRepositoryFactory {
    companion object : PipelineDomainFactory<GitSourceCodeRepository> {
        override val rootPath: String = "repositories.git"
        override val instanceName: String = "GitSourceCodeRepository"

        override suspend fun create(data: Map<String, Any>): GitSourceCodeRepository {
            val gitRepository = getRootMapObject(data)

            val id = IDComponent.create(
                gitRepository.validateAndGet("id")
                    .isString()
                    .throwIfInvalid(getErrorMessage("id"))
            )

            val name = gitRepository.validateAndGet("name")
                .isString()
                .throwIfInvalid(getErrorMessage("name"))

            val description = gitRepository.validateAndGet("description")
                .isString()
                .defaultValueIfInvalid("")

            val branches = gitRepository.validateAndGet("branches")
                .dependsAnyOn(data, "repositories.git.remote", "repositories.git.local")
                .isList()
                .defaultValueIfInvalid(emptyList<String>())

            val globalConfigName = gitRepository.validateAndGet("globalConfigName")
                .isString()
                .defaultValueIfInvalid("")

            val globalConfigEmail = gitRepository.validateAndGet("globalConfigEmail")
                .isString()
                .defaultValueIfInvalid("")

            val extensions = scmExtension(gitRepository)

            val url = gitRepository.validateAndGet("remote.url")
                .isString()
                .throwIfInvalid(getErrorMessage("remote.url"))
                .let { URL(it) }

            val credentialsId = gitRepository.validateAndGet("remote.credentialsId")
                .isString()
                .throwIfInvalid(getErrorMessage("remote.credentialsId"))

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

        private fun scmExtension(gitRepository: Map<String, Any>): List<SCMExtension> {
            val extensionsMap = gitRepository.validateAndGet("extensions")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>())

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

class CleanRepositoryFactory {
    companion object : PipelineDomainFactory<CleanRepository> {
        override val rootPath: String = "repositories.git.extensions.cleanRepository"
        override val instanceName: String = "CleanRepository"
        override suspend fun create(data: Map<String, Any>): CleanRepository {
            return CleanRepository(
                getRootMapObject(data).validateAndGet("clean")
                    .isBoolean()
                    .defaultValueIfInvalid(false)
            )
        }
    }
}