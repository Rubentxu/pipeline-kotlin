package dev.rubentxu.pipeline.backend.cdi

import dev.rubentxu.pipeline.backend.factories.JobInstanceFactory
import dev.rubentxu.pipeline.backend.factories.PipelineContextFactory
import dev.rubentxu.pipeline.backend.factories.SourceCodeRepositoryFactory
import dev.rubentxu.pipeline.backend.factories.SourceCodeRepositoryManagerFactory
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.logger.IPipelineLogger
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module


