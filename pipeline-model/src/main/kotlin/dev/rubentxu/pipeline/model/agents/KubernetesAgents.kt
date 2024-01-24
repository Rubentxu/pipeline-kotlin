package dev.rubentxu.pipeline.model.agents

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.steps.EnvVars

data class KubernetesAgent(
    override val id: IDComponent,
    override val name: String,
    override val labels: List<String>,
    val serverUrl: String,
    val serverCertificate: String,
    val skipTlsVerify: Boolean,
    val credentialsId: String,
    val namespace: String,
    val pipelineUrl: String,
    val pipelineTunnel: String,
    val containerCapStr: Int,
    val maxRequestsPerHostStr: Int,
    val retentionTimeout: Int,
    val connectTimeout: Int,
    val readTimeout: Int,
    val templates: List<K8sTemplate>,
    override val type: String,
) : Agent

data class K8sTemplate(
    val name: String,
    val serviceAccount: String,
    val instanceCap: Int,
    val idleMinutes: Int,
    val label: String?,
    val showRawYaml: Boolean,
    val volumes: List<Volume>,
    val containers: List<Container>,
    val imagePullSecrets: List<String>,
    val envVars: EnvVars?,
) : Template

sealed class Volume : PipelineDomain

data class HostPathVolume(
    val mountPath: String,
    val hostPath: String,
) : Volume()

data class EmptyDirVolume(
    val memory: Boolean,
    val mountPath: String,
) : Volume()

data class ConfigMapVolume(
    val configMapName: String,
    val mountPath: String,
    val subPath: String,
) : Volume()

data class Container(
    val name: String,
    val image: String,
    val privileged: Boolean,
    val alwaysPullImage: Boolean,
    val command: String,
    val args: String,
    val workingDir: String,
    val ttyEnabled: Boolean,
    val resourceRequestCpu: String,
    val resourceRequestMemory: String,
    val resourceLimitCpu: String,
    val resourceLimitMemory: String,
) : PipelineDomain

