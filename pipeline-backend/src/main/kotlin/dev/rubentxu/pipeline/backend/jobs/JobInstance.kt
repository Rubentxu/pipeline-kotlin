package dev.rubentxu.pipeline.backend.jobs

import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.steps.EnvVars

class JobInstance(
    name: String,
    environmentVars: EnvVars,
    publisher: Publisher,
    projectSource: ProjectSource,
    pluginsDefinitionSources: List<PluginsDefinitionSource>,
    pipelineFileSource: PipelineFileSource,
    trigger: Trigger?,
    parameters: List<JobParameter<*>>

): JobDefinition(
    name,
    environmentVars,
    publisher,
    projectSource,
    pluginsDefinitionSources,
    pipelineFileSource,
    trigger

) {


}