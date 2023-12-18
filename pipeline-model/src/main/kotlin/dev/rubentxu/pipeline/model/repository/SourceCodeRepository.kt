package dev.rubentxu.pipeline.model.repository

import dev.rubentxu.pipeline.model.*
import dev.rubentxu.pipeline.model.validations.validateAndGet
import java.net.URL
import java.nio.file.Path

enum class SourceCodeType {
    PROJECT,
    PIPELINE_DEFINITION,
    LIBRARY,
    PLUGIN,
}

interface ISourceCodeManager: PipelineDomain {
    val definitions: Map<IDComponent, SourceCodeRepository>
    fun findSourceRepository(id: IDComponent): SourceCodeRepository
}

interface SCMExtension : PipelineDomain
data class SimpleSCMExtension<T>(
    val name: String,
    val value: T,
) : SCMExtension

interface SourceCodeRepository: IPipelineConfig {
    val id: IDComponent
    val name: String
    val description: String?
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
    val extensions: List<SCMExtension>,
    val branches: List<String>,
    val globalConfigName: String,
    val globalConfigEmail: String,
    val url: URL,
    val credentialsId: String,
) : SourceCodeRepository {

    override fun retrieve(): SourceCode {
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


    override fun retrieve(): SourceCode {
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

    override fun retrieve(): SourceCode {
        TODO("Not yet implemented")
    }
}

class CleanRepository(
    val clean: Boolean
) : SCMExtension

data class LocalSourceCodeRepository(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    val branches: List<String>,
    val path: Path,
    val isBareRepo: Boolean,
    val sourceType: SourceCodeType,
) : SourceCodeRepository {

    override fun retrieve(): SourceCode {
        return SourceCode(id, name, path.toUri().toURL(), sourceType)
    }
}
