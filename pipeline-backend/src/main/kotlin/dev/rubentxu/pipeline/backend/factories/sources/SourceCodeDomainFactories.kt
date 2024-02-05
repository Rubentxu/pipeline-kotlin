package dev.rubentxu.pipeline.backend.factories.sources

import arrow.core.raise.either
import arrow.fx.coroutines.parMap
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.backend.sources.CleanRepository
import dev.rubentxu.pipeline.backend.sources.GitSourceCodeRepository
import dev.rubentxu.pipeline.backend.sources.LocalSourceCodeRepository
import dev.rubentxu.pipeline.backend.sources.Mercurial
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.Res
import dev.rubentxu.pipeline.model.repository.*
import java.net.URL
import java.nio.file.Path

class PluginsDefinitionSourceFactory : PipelineDomain {

    companion object : PipelineDomainFactory<List<PluginSourceCodeConfig>> {
        override val rootPath: String = "plugins"

        override suspend fun create(data: PropertySet): Res<List<PluginSourceCodeConfig>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { plugin ->
                        val name = plugin.required<String>("name")
                        val description = plugin.required<String>("description")
                        val fromFile = plugin.required<String>("fromFile")
                        val fromLocalDirectory = plugin.required<String>("fromLocalDirectory")
                        val fromFilePath = if (fromFile.isNotEmpty()) Path.of(fromFile) else null
                        val fromLocalDirectoryPath =
                            if (fromLocalDirectory.isNotEmpty()) Path.of(fromLocalDirectory) else null

                        val module = plugin.required<String>("module")

                        PluginSourceCodeConfig(
                            name = name,
                            description = description,
                            module = module,
                            fromFile = fromFilePath,
                            fromLocalDirectory = fromLocalDirectoryPath
                        )
                    }?:emptyList()
            }


    }

}


class PipelineFileSourceCodeFactory : PipelineDomain {


    companion object : PipelineDomainFactory<SourceCodeConfig> {
        override val rootPath: String = "pipelineSourceCode"


        override suspend fun create(data: PropertySet): Res<SourceCodeConfig> =
            either {
                val pipelineSourceCode = getRootPropertySet(data)

                val scmReferenceId = IDComponent.create(
                    pipelineSourceCode.required("repository.referenceId")
                )
                val relativeScriptPath = pipelineSourceCode.required<String>("scriptPath")

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


        override suspend fun create(data: PropertySet): Res<SourceCodeConfig> =
            either {
                val projectSourceCode = getRootPropertySet(data)

                val scmReferenceId = IDComponent.create(
                    projectSourceCode.required("repository.referenceId")
                )

                SourceCodeConfig(
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


        override suspend fun create(data: PropertySet): Res<Mercurial> =
            either {
                val repositoryMercurial = getRootPropertySet(data)

                val id =
                    IDComponent.create(
                        repositoryMercurial.required("id")
                    )
                val name: String = repositoryMercurial.required("name")
                val description: String = repositoryMercurial.required("description")
                val branches: List<String> = repositoryMercurial.required("branches")
                val url = repositoryMercurial.required<String>("remote.url").let { URL(it) }
                val credentialsId: String = repositoryMercurial.required("remote.credentialsId")

                Mercurial(
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


        override suspend fun create(data: PropertySet): Res<LocalSourceCodeRepository> =
            either {
                val localRepository = getRootPropertySet(data)

                val id = IDComponent.create(
                    localRepository.required("id")
                )
                val name: String = localRepository.required("name")
                val description: String = localRepository.required("description")

                val path = Path.of(
                    localRepository.required<String>("path")
                )
                LocalSourceCodeRepository(
                    id = id,
                    name = name,
                    description = description,
                    path = Path.of(data.required<String>("git.local.path")),
                )
            }
    }
}


class GitSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<GitSourceCodeRepository> {
        override val rootPath: String = "repositories.git"


        override suspend fun create(data: PropertySet): Res<GitSourceCodeRepository> =
            either {
                val gitRepository = getRootPropertySet(data)

                val id = IDComponent.create(gitRepository.required("id"))
                val name: String = gitRepository.required("name")
                val description: String = gitRepository.required("description")
                val branches: List<String> = gitRepository.required("branches")
                val globalConfigName: String = gitRepository.required("globalConfigName")
                val globalConfigEmail: String = gitRepository.required("globalConfigEmail")

                val extensions = scmExtension(gitRepository).bind()

                val url = gitRepository.required<String>("remote.url").let { URL(it) }
                val credentialsId: String = gitRepository.required("remote.credentialsId")

                GitSourceCodeRepository(
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

        private fun scmExtension(gitRepository: PropertySet): Res<List<SCMExtension>> =
            either {
                val extensionsMap = gitRepository.required<PropertySet>("extensions")

                extensionsMap.map {
                    when (it.key) {
                        "sparseCheckoutPaths" -> SimpleSCMExtension(
                            "sparseCheckoutPaths",
                            extensionsMap.required<List<String>>("sparseCheckoutPaths")
                        )

                        "cloneOptions" -> SimpleSCMExtension(
                            "cloneOptions",
                            extensionsMap.required("cloneOptions")
                        )

                        "relativeTargetDirectory" -> SimpleSCMExtension(
                            "relativeTargetDirectory",
                            extensionsMap.required("relativeTargetDirectory")
                        )

                        "shallowClone" -> SimpleSCMExtension(
                            "shallowClone",
                            extensionsMap.required("shallowClone")
                        )

                        "timeout" -> SimpleSCMExtension(
                            "timeout",
                            extensionsMap.required("timeout")
                        )

                        "lfs" -> SimpleSCMExtension(
                            "lfs",
                            extensionsMap.required("lfs")
                        )

                        "submodules" -> SimpleSCMExtension(
                            "submodules",
                            extensionsMap.required("submodules")
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


        override suspend fun create(data: PropertySet): Res<CleanRepository> =
            either {
                CleanRepository(
                    getRootPropertySet(data).required("clean")
                )
            }
    }
}