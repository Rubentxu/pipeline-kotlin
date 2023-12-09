package dev.rubentxu.pipeline.model.retrievers

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import java.io.File


interface SourceRetriever {

    fun retrieve(config: IPipelineConfig): File
}

interface ProjectSourceRetriever: SourceRetriever

interface LibrarySourceRetriever: SourceRetriever

interface PipelineSourceRetriever: SourceRetriever