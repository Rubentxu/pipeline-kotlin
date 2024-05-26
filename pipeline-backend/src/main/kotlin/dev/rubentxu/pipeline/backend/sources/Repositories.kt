package dev.rubentxu.pipeline.backend.sources

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.core.jobs.PluginsDefinitionSource
import dev.rubentxu.pipeline.model.jobs.ProjectSourceCode
import dev.rubentxu.pipeline.model.repository.*
import java.net.URL
import java.nio.file.Path


data class GitSourceCodeRepository(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    val extensions: List<SCMExtension>,
    val branches: List<String>,
    val globalConfigName: String,
    val globalConfigEmail: String,
    val url: URL,
    val credentialsId: String,
) : SourceCodeRepository {

    override suspend fun retrieve(config: SourceCodeConfig, context: IPipelineContext): SourceCode {
        TODO("Not yet implemented")
    }
}

data class SvnSourceCodeRepository(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    val extensions: List<SCMExtension>,
    val branches: List<String>,
    val url: URL,
    val credentialsId: String,
) : SourceCodeRepository {


    override suspend fun retrieve(config: SourceCodeConfig, context: IPipelineContext): SourceCode {
        TODO("Not yet implemented")
    }
}


data class Mercurial(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    val extensions: List<SCMExtension>,
    val branches: List<String>,
    val url: URL,
    val credentialsId: String,
) : SourceCodeRepository {

    override suspend fun retrieve(config: SourceCodeConfig, context: IPipelineContext): SourceCode {
        TODO("Not yet implemented")
    }
}

class CleanRepository(
    val clean: Boolean,
) : SCMExtension

data class LocalSourceCodeRepository(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    val path: Path,

    ) : SourceCodeRepository {

    override suspend fun retrieve(config: SourceCodeConfig, context: IPipelineContext): SourceCode {

        return when (config.sourceCodeType) {
            SourceCodeType.PROJECT -> ProjectSourceCode(
                id = id,
                name = name,
                description = description,
                path = path,
            )

            SourceCodeType.PIPELINE_DEFINITION -> PipelineScriptSourceCode(
                id = id,
                name = name,
                description = description,
                path = path,
            )

            SourceCodeType.PLUGIN -> PluginsDefinitionSource(
                id = id,
                name = name,
                description = description,
                path = path,
            )
        }
    }
}
