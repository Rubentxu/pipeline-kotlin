package dev.rubentxu.pipeline.model.agents

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain


data class DockerAgent(
    override val id: IDComponent,
    override val name: String,
    override val labels: List<String>,
    val dockerHost: String,
    val templates: List<DockerTemplate>,
) : Agent

data class DockerTemplate(
    val labelString: String,
    val dockerTemplateBase: DockerTemplateBase,
    val remoteFs: String,
    val user: String,
    val instanceCapStr: String,
    val retentionStrategy: RetentionStrategy,
) : Template

data class DockerTemplateBase(
    val image: String,
    val mounts: List<String>,
    val environmentsString: String,
) : PipelineDomain

data class RetentionStrategy(
    val idleMinutes: Int,
) : PipelineDomain