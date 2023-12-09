package dev.rubentxu.pipeline.model.retrievers

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import java.io.File


interface SourceRetriever<T> where T: IPipelineConfig {

    fun retrieve(config: T): File
}

interface ProjectSourceRetriever<T>: SourceRetriever<T> where T: IPipelineConfig

interface LibrarySourceRetriever<T>: SourceRetriever<T> where T: IPipelineConfig

interface PipelineSourceRetriever<T>: SourceRetriever<T> where T: IPipelineConfig