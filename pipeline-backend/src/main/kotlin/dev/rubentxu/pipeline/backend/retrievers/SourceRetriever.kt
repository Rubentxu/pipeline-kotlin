package dev.rubentxu.pipeline.backend.retrievers

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import java.io.File

interface SourceRetriever {

    fun retrieve(config: IPipelineConfig): File
}

interface ProjectSourceRetriever: SourceRetriever

interface LibrarySourceRetriever: SourceRetriever

interface PipelineSourceRetriever: SourceRetriever

class ProjectLocalSourceRetriever: ProjectSourceRetriever {
    override fun retrieve(config: IPipelineConfig): File {
        TODO()
    }
}

class LibraryLocalSourceRetriever: LibrarySourceRetriever {
    override fun retrieve(config: IPipelineConfig): File {
        TODO()
    }
}

class PipelineLocalSourceRetriever: PipelineSourceRetriever {
    override fun retrieve(config: IPipelineConfig): File {
        TODO()
    }
}

class ProjectSCMSourceRetriever: ProjectSourceRetriever {
    override fun retrieve(config: IPipelineConfig): File {
        TODO()
    }
}

class LibrarySCMSourceRetriever: LibrarySourceRetriever {
    override fun retrieve(config: IPipelineConfig): File {
        TODO()
    }
}

class PipelineSCMSourceRetriever: PipelineSourceRetriever {
    override fun retrieve(config: IPipelineConfig): File {
        TODO()
    }
}