package dev.rubentxu.pipeline.backend.factories.sources

import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.backend.sources.CleanRepository
import dev.rubentxu.pipeline.backend.sources.GitSourceCodeRepository
import dev.rubentxu.pipeline.backend.sources.LocalSourceCodeRepository
import dev.rubentxu.pipeline.backend.sources.Mercurial
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineError
import dev.rubentxu.pipeline.model.repository.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URL
import java.nio.file.Path

class PluginsDefinitionSourceFactory : PipelineDomain {

    companion object : PipelineDomainFactory<List<PluginSourceCodeConfig>> {
        override val rootPath: String = "plugins"

        override suspend fun create(data: PropertySet): Result<List<PluginSourceCodeConfig>> = runCatching {
            val pluginsProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val plugins = pluginsProperties.map { plugin ->
                    async { createPluginSourceCodeConfig(plugin).getOrThrow() }
                }
                plugins.awaitAll()
            }
        }

        suspend fun createPluginSourceCodeConfig(plugin: PropertySet): Result<PluginSourceCodeConfig> {
            return runCatching {
                val name = plugin.required<String>("name").getOrThrow()
                val description = plugin.required<String>("description").getOrThrow()
                val fromFile = plugin.required<String>("fromFile").getOrThrow()
                val fromLocalDirectory = plugin.required<String>("fromLocalDirectory").getOrThrow()
                val module = plugin.required<String>("module").getOrThrow()

                val fromFilePath = if (fromFile.isNotEmpty()) Path.of(fromFile) else null
                val fromLocalDirectoryPath =
                    if (fromLocalDirectory.isNotEmpty()) Path.of(fromLocalDirectory) else null

                PluginSourceCodeConfig(
                    name = name,
                    description = description,
                    module = module,
                    fromFile = fromFilePath,
                    fromLocalDirectory = fromLocalDirectoryPath
                )
            }
        }
    }
}


class PipelineFileSourceCodeFactory : PipelineDomain {


    companion object : PipelineDomainFactory<SourceCodeConfig> {
        override val rootPath: String = "pipelineSourceCode"


        override suspend fun create(data: PropertySet): Result<SourceCodeConfig> = runCatching {
            val pipelineSourceCode = getRootPropertySet(data)

            val scmReferenceId = IDComponent.create(
                pipelineSourceCode.required<String>("repository.referenceId").getOrThrow()
            )
            val relativeScriptPath = pipelineSourceCode.required<String>("scriptPath").getOrThrow()

            SourceCodeConfig(
                repositoryId = scmReferenceId,
                relativePath = Path.of(relativeScriptPath),
                sourceCodeType = SourceCodeType.PIPELINE_DEFINITION
            )
        }
    }

}

class ProjectSourceCodeFactory : PipelineDomain {

    companion object : PipelineDomainFactory<SourceCodeConfig> {
        override val rootPath: String = "projectSourceCode"

        override suspend fun create(data: PropertySet): Result<SourceCodeConfig> = runCatching {
            val projectSourceCode = getRootPropertySet(data)

            val scmReferenceId = IDComponent.create(
                projectSourceCode.required<String>("repository.referenceId").getOrThrow()
            )

            SourceCodeConfig(
                repositoryId = scmReferenceId, relativePath = null, sourceCodeType = SourceCodeType.PROJECT
            )
        }
    }

}


class MercurialSourceCodeRepositoryFactory {


    companion object : PipelineDomainFactory<Mercurial> {
        override val rootPath: String = "repositories.mercurial"


        override suspend fun create(data: PropertySet): Result<Mercurial> = runCatching {
            val repositoryMercurial = getRootPropertySet(data)

            val id = repositoryMercurial.required<String>("id").getOrThrow()
            val name = repositoryMercurial.required<String>("name").getOrThrow()
            val description = repositoryMercurial.required<String>("description").getOrThrow()
            val branches = repositoryMercurial.required<List<String>>("branches").getOrThrow()
            val url = repositoryMercurial.required<String>("remote.url").getOrThrow()
            val credentialsId = repositoryMercurial.required<String>("remote.credentialsId").getOrThrow()

            Mercurial(
                id = IDComponent.create(id),
                name = name,
                description = description,
                extensions = emptyList(),
                branches = branches,
                url = URL(url),
                credentialsId = credentialsId,
            )
        }
    }
}


class LocalGitSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<LocalSourceCodeRepository> {
        override val rootPath: String = "repositories.local"


        override suspend fun create(data: PropertySet): Result<LocalSourceCodeRepository> = runCatching {
            val localRepository = getRootPropertySet(data)

            val id = IDComponent.create(
                localRepository.required<String>("id").getOrThrow()
            )
            val name: String = localRepository.required<String>("name").getOrThrow()
            val description: String = localRepository.required<String>("description").getOrThrow()


            LocalSourceCodeRepository(
                id = id,
                name = name,
                description = description,
                path = Path.of(data.required<String>("git.local.path").getOrThrow()),
            )
        }
    }
}


class GitSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<GitSourceCodeRepository> {
        override val rootPath: String = "repositories.git"

        override suspend fun create(data: PropertySet): Result<GitSourceCodeRepository> {
            val gitRepository = getRootPropertySet(data)

            return coroutineScope {
                val id = async { gitRepository.required<String>("id").getOrThrow() }
                val name = async { gitRepository.required<String>("name").getOrThrow() }
                val description = async { gitRepository.required<String>("description").getOrThrow() }
                val branches = async { gitRepository.required<List<String>>("branches").getOrThrow() }
                val globalConfigName = async { gitRepository.required<String>("globalConfigName").getOrThrow() }
                val globalConfigEmail = async { gitRepository.required<String>("globalConfigEmail").getOrThrow() }
                val extensions = async { scmExtension(gitRepository) }
                val url = async { gitRepository.required<String>("remote.url").getOrThrow() }
                val credentialsId = async { gitRepository.required<String>("remote.credentialsId").getOrThrow() }

                runCatching {
                    GitSourceCodeRepository(
                        id = IDComponent.create(id.await()),
                        branches = branches.await(),
                        name = name.await(),
                        description = description.await(),
                        globalConfigName = globalConfigName.await(),
                        globalConfigEmail = globalConfigEmail.await(),
                        extensions = extensions.await(),
                        url = URL(url.await()),
                        credentialsId = credentialsId.await(),
                    )
                }
            }
        }

        private suspend fun scmExtension(gitRepository: PropertySet): List<SCMExtension> = coroutineScope {
            val extensionsMap = gitRepository.required<PropertySet>("extensions").getOrThrow()

            val extensions = extensionsMap.map {
                async {
                    when (it.key) {
                        "sparseCheckoutPaths" -> SimpleSCMExtension(
                            "sparseCheckoutPaths",
                            extensionsMap.required<List<String>>("sparseCheckoutPaths").getOrThrow()
                        )

                        "cloneOptions" -> SimpleSCMExtension(
                            "cloneOptions", extensionsMap.required<String>("cloneOptions").getOrThrow()
                        )

                        "relativeTargetDirectory" -> SimpleSCMExtension(
                            "relativeTargetDirectory",
                            extensionsMap.required<String>("relativeTargetDirectory").getOrThrow()
                        ) as SCMExtension

                        "shallowClone" -> SimpleSCMExtension(
                            "shallowClone", extensionsMap.required<String>("shallowClone").getOrThrow()
                        )

                        "timeout" -> SimpleSCMExtension(
                            "timeout", extensionsMap.required<String>("timeout").getOrThrow()
                        )

                        "lfs" -> SimpleSCMExtension(
                            "lfs", extensionsMap.required<String>("lfs").getOrThrow()
                        )

                        "submodules" -> SimpleSCMExtension(
                            "submodules", extensionsMap.required<String>("submodules").getOrThrow()
                        )

                        else -> throw PipelineError("Invalid SCM extension type for '${extensionsMap.keys.first()}'")
                    }
                }
            }
            extensions.awaitAll()
        }
    }
}

class CleanRepositoryFactory {

    companion object : PipelineDomainFactory<CleanRepository> {
        override val rootPath: String = "repositories.git.extensions.cleanRepository"

        override suspend fun create(data: PropertySet): Result<CleanRepository> = coroutineScope {
            val clean = async { getRootPropertySet(data).required<Boolean>("clean").getOrThrow() }

            runCatching {
                CleanRepository(
                    clean = clean.await()
                )
            }
        }
    }
}