package dev.rubentxu.pipeline.backend.retrievers

import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.retrievers.LibrarySourceRetriever
import dev.rubentxu.pipeline.model.retrievers.PipelineSourceRetriever
import dev.rubentxu.pipeline.model.retrievers.ProjectSourceRetriever
import dev.rubentxu.pipeline.model.workspace.WorkspaceManager
import java.io.File


class ProjectLocalSourceRetriever(workspaceManager: WorkspaceManager): ProjectSourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): File {

    }
}

class ProjectSCMSourceRetriever: ProjectSourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): File {
        TODO()
    }
}

class LibraryLocalSourceRetriever: LibrarySourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): File {
        TODO()
    }
}

class LibrarySCMSourceRetriever: LibrarySourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): File {
        TODO()
    }
}

class PipelineLocalSourceRetriever: PipelineSourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): File {
        TODO()
    }
}

class PipelineSCMSourceRetriever: PipelineSourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): File {
        TODO()
    }
}