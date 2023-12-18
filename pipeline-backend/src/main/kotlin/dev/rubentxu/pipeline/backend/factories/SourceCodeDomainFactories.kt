package dev.rubentxu.pipeline.backend.factories

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.jobs.PipelineFileSource
import dev.rubentxu.pipeline.model.jobs.PluginsDefinitionSource
import dev.rubentxu.pipeline.model.repository.*
import dev.rubentxu.pipeline.model.validations.validateAndGet
import java.net.URL
import java.nio.file.Path



class PluginsDefinitionSourceFactory : PipelineDomain {

    companion object : PipelineDomainFactory<PluginsDefinitionSource> {
        override fun create(data: Map<String, Any>): PluginsDefinitionSource {
            return PluginsDefinitionSource(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid("name is required in LibrarySource"),
                scmReferenceId = IDComponent.create(
                    data.validateAndGet("scmReferenceId")
                        .isString()
                        .throwIfInvalid("scmReferenceId is required in LibrarySource")
                )
            )
        }
    }

}


class PipelineFileSourceCodeFactory : PipelineDomain {

    companion object : PipelineDomainFactory<PipelineFileSource> {
        override fun create(data: Map<String, Any>): PipelineFileSource {
            val name = data.validateAndGet("pipelineFileSource.name").isString()
                .throwIfInvalid("name is required in PipelineFileSource")
            val scmReferenceId = IDComponent.create(
                data.validateAndGet("pipelineFileSource.scmReferenceId")
                    .isString()
                    .throwIfInvalid("scmReferenceId is required in PipelineFileSource")
            )

            val relativeScriptPath = data.validateAndGet("pipelineFileSource.relativeScriptPath")
                .isString()
                .throwIfInvalid("scriptPath is required in PipelineFileSource")

            return PipelineFileSource(
                name = name,
                relativeScriptPath = Path.of(relativeScriptPath),
                scmReferenceId = scmReferenceId,
            )
        }
    }

}

class SvnSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<SvnSourceCodeRepository> {
        override fun create(data: Map<String, Any>): SvnSourceCodeRepository {

            val id = IDComponent.create(
                data.validateAndGet("svn.id")
                    .isString()
                    .throwIfInvalid("id is required in Svn Repository")
            )

            val name = data.validateAndGet("svn.name")
                .isString()
                .defaultValueIfInvalid(id.toString())

            val description = data.validateAndGet("svn.description")
                .isString()
                .defaultValueIfInvalid("")

            val branches = data.validateAndGet("svn.branches")
                .isList()
                .defaultValueIfInvalid(emptyList<String>())

            val url = data.validateAndGet("svn.remote.url")
                .isString()
                .throwIfInvalid("url is required in Svn Repository")
                .let { URL(it) }

            val credentialsId = data.validateAndGet("svn.remote.credentialsId")
                .isString()
                .throwIfInvalid("credentialsId is required in Svn Repository")

            return SvnSourceCodeRepository(
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


class MercurialSourceCodeRepositoryFactory {

    companion object : PipelineDomainFactory<Mercurial> {
        override fun create(data: Map<String, Any>): Mercurial {

            val id =
                IDComponent.create(
                    data.validateAndGet("mercurial.id").isString().throwIfInvalid("id is required in Mercurial")
                )

            val name = data.validateAndGet("mercurial.name")
                .isString()
                .defaultValueIfInvalid(id.toString())

            val description = data.validateAndGet("mercurial.description")
                .isString()
                .defaultValueIfInvalid("")

            val branches = data.validateAndGet("mercurial.branches")
                .isList()
                .defaultValueIfInvalid(emptyList<String>())

            val url = data.validateAndGet("mercurial.remote.url")
                .isString()
                .throwIfInvalid("url is required in Svn Repository")
                .let { URL(it) }

            val credentialsId = data.validateAndGet("mercurial.remote.credentialsId")
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
        override fun create(data: Map<String, Any>): LocalSourceCodeRepository {
            val id = IDComponent.create(
                data.validateAndGet("git.id")
                    .isString()
                    .throwIfInvalid("id is required in LocalRepository")
            )

            val name = data.validateAndGet("git.name")
                .isString()
                .defaultValueIfInvalid(id.toString())


            return LocalSourceCodeRepository(
                id = id,
                name = name,
                description = data.validateAndGet("git.description")
                    .isString()
                    .defaultValueIfInvalid(""),
                branches = data.validateAndGet("git.branches")
                    .isList()
                    .defaultValueIfInvalid(emptyList<String>()),
                path = Path.of(
                    data.validateAndGet("git.local.path")
                        .isString()
                        .throwIfInvalid("path is required in LocalRepository")
                ),
                isBareRepo = data.validateAndGet("git.local.isBareRepo")
                    .isBoolean()
                    .defaultValueIfInvalid(false),
                sourceType = data.validateAndGet("git.sourceType")
                    .isString()
                    .defaultValueIfInvalid("PIPELINE_DEFINITION")
                    .let { SourceCodeType.valueOf(it) }

            )
        }
    }
}


class GitSourceCodeRepositoryFactory  {
    companion object : PipelineDomainFactory<GitSourceCodeRepository> {
        override fun create(data: Map<String, Any>): GitSourceCodeRepository {

            val id = IDComponent.create(
                data.validateAndGet("git.id").isString().throwIfInvalid("id is required in GitScmConfig")
            )

            val name = data.validateAndGet("git.name")
                .isString()
                .defaultValueIfInvalid(id.toString())

            val description = data.validateAndGet("git.description")
                .isString()
                .defaultValueIfInvalid("")

            val branches = data.validateAndGet("git.branches")
                .dependsAnyOn(data, "git.remote", "git.local")
                .isList()
                .defaultValueIfInvalid(emptyList<String>())

            val globalConfigName = data.validateAndGet("git.globalConfigName")
                .isString()
                .defaultValueIfInvalid("")

            val globalConfigEmail = data.validateAndGet("git.globalConfigEmail")
                .isString()
                .defaultValueIfInvalid("")

            val extensionsMap = data.validateAndGet("git.extensions")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>())

            val extensions = scmExtension(extensionsMap)

            val url = data.validateAndGet("git.remote.url")
                .isString()
                .throwIfInvalid("url is required in GitScmConfig")
                .let { URL(it) }

            val credentialsId = data.validateAndGet("git.remote.credentialsId")
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
}

class CleanRepositoryFactory {
    companion object : PipelineDomainFactory<CleanRepository> {
        override fun create(data: Map<String, Any>): CleanRepository {
            return CleanRepository(
                data.validateAndGet("cleanRepository")
                    .isBoolean()
                    .defaultValueIfInvalid(false)
            )
        }
    }
}