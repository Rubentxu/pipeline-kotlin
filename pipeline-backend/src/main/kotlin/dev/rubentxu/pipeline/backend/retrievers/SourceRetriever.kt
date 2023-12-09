package dev.rubentxu.pipeline.backend.retrievers

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.retrievers.LibrarySourceRetriever
import dev.rubentxu.pipeline.model.retrievers.PipelineSourceRetriever
import dev.rubentxu.pipeline.model.retrievers.ProjectSourceRetriever
import java.io.File


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