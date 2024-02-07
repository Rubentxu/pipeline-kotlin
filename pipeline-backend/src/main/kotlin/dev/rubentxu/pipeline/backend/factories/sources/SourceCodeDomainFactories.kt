package dev.rubentxu.pipeline.backend.factories.sources

import arrow.core.raise.result
import arrow.fx.coroutines.parMap
import dev.rubentxu.pipeline.backend.coroutines.parZipResult
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.backend.sources.CleanRepository
import dev.rubentxu.pipeline.backend.sources.GitSourceCodeRepository
import dev.rubentxu.pipeline.backend.sources.LocalSourceCodeRepository
import dev.rubentxu.pipeline.backend.sources.Mercurial
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain

import dev.rubentxu.pipeline.model.repository.*
import java.net.URL
import java.nio.file.Path

class PluginsDefinitionSourceFactory : PipelineDomain {

    companion object : PipelineDomainFactory<List<PluginSourceCodeConfig>> {
        override val rootPath: String = "plugins"

        override suspend fun create(data: PropertySet): Result<List<PluginSourceCodeConfig>> = result {
            getRootListPropertySet(data)?.parMap { plugin ->
                createPluginSourceCodeConfig(plugin).bind()
            } ?: emptyList()

        }

        suspend fun createPluginSourceCodeConfig(plugin: PropertySet): Result<PluginSourceCodeConfig> {
            return parZipResult(
                { plugin.required<String>("name") },
                { plugin.required<String>("description") },
                { plugin.required<String>("fromFile") },
                { plugin.required<String>("fromLocalDirectory") },
                { plugin.required<String>("module") }
            ) { name, description, fromFile, fromLocalDirectory, module ->
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


        override suspend fun create(data: PropertySet): Result<SourceCodeConfig> = result {
            val pipelineSourceCode = getRootPropertySet(data)

            val scmReferenceId = IDComponent.create(
                pipelineSourceCode.required<String>("repository.referenceId").bind()
            )
            val relativeScriptPath = pipelineSourceCode.required<String>("scriptPath").bind()

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

        override suspend fun create(data: PropertySet): Result<SourceCodeConfig> = result {
            val projectSourceCode = getRootPropertySet(data)

            val scmReferenceId = IDComponent.create(
                projectSourceCode.required<String>("repository.referenceId").bind()
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


        override suspend fun create(data: PropertySet): Result<Mercurial> {
            val repositoryMercurial = getRootPropertySet(data)

            return parZipResult(
                { repositoryMercurial.required<String>("id") },
                { repositoryMercurial.required<String>("name") },
                { repositoryMercurial.required<String>("description") },
                { repositoryMercurial.required<List<String>>("branches") },
                { repositoryMercurial.required<String>("remote.url") },
                { repositoryMercurial.required<String>("remote.credentialsId") }
            ) { id, name, description, branches, url, credentialsId ->
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
}


class LocalGitSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<LocalSourceCodeRepository> {
        override val rootPath: String = "repositories.local"


        override suspend fun create(data: PropertySet): Result<LocalSourceCodeRepository> = result {
            val localRepository = getRootPropertySet(data)

            val id = IDComponent.create(
                localRepository.required<String>("id").bind()
            )
            val name: String = localRepository.required<String>("name").bind()
            val description: String = localRepository.required<String>("description").bind()

            val path = Path.of(
                localRepository.required<String>("path").bind()
            )
            LocalSourceCodeRepository(
                id = id,
                name = name,
                description = description,
                path = Path.of(data.required<String>("git.local.path").bind()),
            )
        }
    }
}


class GitSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<GitSourceCodeRepository> {
        override val rootPath: String = "repositories.git"


        override suspend fun create(data: PropertySet): Result<GitSourceCodeRepository>  {
            val gitRepository = getRootPropertySet(data)

           return parZipResult(
                { gitRepository.required<String>("id") },
                { gitRepository.required<String>("name") },
                { gitRepository.required<String>("description") },
                { gitRepository.required<List<String>>("branches") },
                { gitRepository.required<String>("globalConfigName") },
                { gitRepository.required<String>("globalConfigEmail") },
                { scmExtension(gitRepository) },
                { gitRepository.required<String>("remote.url") },
                { gitRepository.required<String>("remote.credentialsId") }
            ) { id, name, description, branches, globalConfigName, globalConfigEmail, extensions, url, credentialsId ->

                GitSourceCodeRepository(
                    id = IDComponent.create(id),
                    branches = branches,
                    name = name,
                    description = description,
                    globalConfigName = globalConfigName,
                    globalConfigEmail = globalConfigEmail,
                    extensions = extensions,
                    url = URL(url),
                    credentialsId = credentialsId,
                )
            }


        }

        private fun scmExtension(gitRepository: PropertySet): Result<List<SCMExtension>> = result {
            val extensionsMap = gitRepository.required<PropertySet>("extensions").bind()

            extensionsMap.map {
                when (it.key) {
                    "sparseCheckoutPaths" -> SimpleSCMExtension(
                        "sparseCheckoutPaths", extensionsMap.required<List<String>>("sparseCheckoutPaths")
                    )

                    "cloneOptions" -> SimpleSCMExtension(
                        "cloneOptions", extensionsMap.required("cloneOptions")
                    )

                    "relativeTargetDirectory" -> SimpleSCMExtension(
                        "relativeTargetDirectory", extensionsMap.required("relativeTargetDirectory")
                    )

                    "shallowClone" -> SimpleSCMExtension(
                        "shallowClone", extensionsMap.required("shallowClone")
                    )

                    "timeout" -> SimpleSCMExtension(
                        "timeout", extensionsMap.required("timeout")
                    )

                    "lfs" -> SimpleSCMExtension(
                        "lfs", extensionsMap.required("lfs")
                    )

                    "submodules" -> SimpleSCMExtension(
                        "submodules", extensionsMap.required("submodules")
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


        override suspend fun create(data: PropertySet): Result<CleanRepository> = result {
            CleanRepository(
                getRootPropertySet(data).required<Boolean>("clean").bind()
            )
        }
    }
}