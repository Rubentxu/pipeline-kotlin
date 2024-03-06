package dev.rubentxu.pipeline.backend.factories.agents

import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.agents.*
import dev.rubentxu.pipeline.model.steps.EnvVars

class KubernetesAgentFactory {

    companion object : PipelineDomainFactory<List<KubernetesAgent>> {
        override val rootPath: String = "agents.clouds[*].kubernetes"

        override suspend fun create(data: PropertySet): Result<List<KubernetesAgent>> = runCatching {
            val result = getRootListPropertySet(data)
                ?.map { properties: PropertySet ->
                    kubernetesAgent(
                        id = IDComponent.create(properties.required<String>("id").getOrThrow()),
                        properties = properties,
                        templates = KubernetesTemplateFactory.create(properties).getOrThrow(),
                        labels = properties.required<List<String>>("labels").getOrThrow()
                    ).getOrThrow()
                } ?: emptyList()

            result
        }

        private suspend fun kubernetesAgent(
            id: IDComponent,
            properties: PropertySet,
            templates: List<K8sTemplate>,
            labels: List<String>,
        ): Result<KubernetesAgent> {
            return runCatching {
                val name = properties.required<String>("name").getOrThrow()
                val serverUrl = properties.required<String>("serverUrl").getOrThrow()
                val serverCertificate = properties.required<String>("serverCertificate").getOrThrow()
                val skipTlsVerify = properties.required<Boolean>("skipTlsVerify").getOrThrow()
                val credentialsId = properties.required<String>("credentialsId").getOrThrow()
                val namespace = properties.required<String>("namespace").getOrThrow()
                val pipelineUrl = properties.required<String>("pipelineUrl").getOrThrow()
                val pipelineTunnel = properties.required<String>("pipelineTunnel").getOrThrow()
                val containerCapStr = properties.required<Int>("containerCapStr").getOrThrow()
                val maxRequestsPerHostStr = properties.required<Int>("maxRequestsPerHostStr").getOrThrow()
                val retentionTimeout = properties.required<Int>("retentionTimeout").getOrThrow()
                val connectTimeout = properties.required<Int>("connectTimeout").getOrThrow()
                val readTimeout = properties.required<Int>("readTimeout").getOrThrow()

                KubernetesAgent(
                    id = id,
                    name = name,
                    serverUrl = serverUrl,
                    serverCertificate = serverCertificate,
                    skipTlsVerify = skipTlsVerify,
                    credentialsId = credentialsId,
                    namespace = namespace,
                    pipelineUrl = pipelineUrl,
                    pipelineTunnel = pipelineTunnel,
                    containerCapStr = containerCapStr,
                    maxRequestsPerHostStr = maxRequestsPerHostStr,
                    retentionTimeout = retentionTimeout,
                    connectTimeout = connectTimeout,
                    readTimeout = readTimeout,
                    templates = templates,
                    labels = labels,
                    type = "kubernetes"
                )
            }
        }
    }
}

class KubernetesTemplateFactory {

    companion object : PipelineDomainFactory<List<K8sTemplate>> {
        override val rootPath: String = "templates"

        override suspend fun create(kubernetesProperties: PropertySet): Result<List<K8sTemplate>> = runCatching {
            val result = getRootListPropertySet(kubernetesProperties)
                ?.map { templateProperties ->
                    createK8sTemplate(templateProperties)
                } ?: emptyList()

            result
        }

        suspend fun createK8sTemplate(templateProperties: PropertySet): K8sTemplate {
            val name = templateProperties.required<String>("name").getOrThrow()
            val serviceAccount = templateProperties.required<String>("serviceAccount").getOrThrow()
            val instanceCap = templateProperties.required<Int>("instanceCap").getOrThrow()
            val idleMinutes = templateProperties.required<Int>("idleMinutes").getOrThrow()
            val label = templateProperties.required<String>("label").getOrThrow()
            val showRawYaml = templateProperties.required<Boolean>("showRawYaml").getOrThrow()
            val volumes = VolumeFactory.create(templateProperties).getOrThrow()
            val containers = ContainerFactory.create(templateProperties).getOrThrow()
            val imagePullSecrets = templateProperties.required<List<String>>("imagePullSecrets[*].name").getOrThrow()

            val envVarsMap = templateProperties.required<List<PropertySet>>("envVars").getOrThrow()
                .map {
                    it.required<String>("key").getOrThrow() to it.required<String>("value").getOrThrow()
                }.toMap()

            val envVars = EnvVars(envVarsMap)

            return K8sTemplate(
                name = name,
                serviceAccount = serviceAccount,
                instanceCap = instanceCap,
                idleMinutes = idleMinutes,
                label = label,
                showRawYaml = showRawYaml,
                volumes = volumes,
                containers = containers,
                imagePullSecrets = imagePullSecrets,
                envVars = envVars
            )
        }
    }
}

class VolumeFactory {

    companion object : PipelineDomainFactory<List<Volume>> {
        override val rootPath: String = "volumes"


        override suspend fun create(templateProperties: PropertySet): Result<List<Volume>> {
            return createVolume(templateProperties)
        }

        private suspend fun createVolume(volumeProperties: PropertySet): Result<List<Volume>> =
            runCatching {
                val hostPathVolume = HostPathVolumeFactory.create(volumeProperties).getOrThrow()
                val emptyDirVolume = EmptyDirVolumeFactory.create(volumeProperties).getOrThrow()
                val configMapVolume = ConfigMapVolumeFactory.create(volumeProperties).getOrThrow()

                buildList {
                    addAll(hostPathVolume)
                    addAll(emptyDirVolume)
                    addAll(configMapVolume)
                }
            }
    }

}

class HostPathVolumeFactory {

    companion object : PipelineDomainFactory<List<HostPathVolume>> {
        override val rootPath: String = "volumes[*].hostPathVolume"

        override suspend fun create(data: PropertySet): Result<List<HostPathVolume>> =
            runCatching {
                val rootListPropertySet = getRootListPropertySet(data) ?: emptyList()
                val hostPathVolumes = rootListPropertySet.map { hostPathVolumeProperties ->
                    HostPathVolume(
                        mountPath = hostPathVolumeProperties.required<String>("mountPath").getOrThrow(),
                        hostPath = hostPathVolumeProperties.required<String>("hostPath").getOrThrow()
                    )
                }
                hostPathVolumes
            }
    }
}

class EmptyDirVolumeFactory {
    companion object : PipelineDomainFactory<List<EmptyDirVolume>> {
        override val rootPath: String = "volumes[*].emptyDirVolume"

        override suspend fun create(data: PropertySet): Result<List<EmptyDirVolume>> =
            runCatching {
                val rootListPropertySet = getRootListPropertySet(data) ?: emptyList()
                val emptyDirVolumes = rootListPropertySet.map { emptyDirVolumeProperties ->
                    EmptyDirVolume(
                        memory = emptyDirVolumeProperties.required<Boolean>("memory").getOrThrow(),
                        mountPath = emptyDirVolumeProperties.required<String>("mountPath").getOrThrow()
                    )
                }
                emptyDirVolumes
            }
    }
}

class ConfigMapVolumeFactory {

    companion object : PipelineDomainFactory<List<ConfigMapVolume>> {
        override val rootPath: String = "volumes[*].configMapVolume"

        override suspend fun create(data: PropertySet): Result<List<ConfigMapVolume>> =
            runCatching {
                val rootListPropertySet = getRootListPropertySet(data) ?: emptyList()
                val configMapVolumes = rootListPropertySet.map { configMapVolumeProperties ->
                    ConfigMapVolume(
                        configMapName = configMapVolumeProperties.required<String>("configMapName").getOrThrow(),
                        mountPath = configMapVolumeProperties.required<String>("mountPath").getOrThrow(),
                        subPath = configMapVolumeProperties.required<String>("subPath").getOrThrow()
                    )
                }
                configMapVolumes
            }
    }
}


class ContainerFactory {

    companion object : PipelineDomainFactory<List<Container>> {
        override val rootPath: String = "containers"

        override suspend fun create(data: PropertySet): Result<List<Container>> =
            runCatching {
                val rootListPropertySet = getRootListPropertySet(data) ?: emptyList()
                val containers = rootListPropertySet.map { containerProperties ->
                    createContainer(containerProperties)
                }
                containers
            }

        private suspend fun createContainer(data: PropertySet): Container {
            val name = data.required<String>("name").getOrThrow()
            val image = data.required<String>("image").getOrThrow()
            val privileged = data.required<Boolean>("privileged").getOrThrow()
            val alwaysPullImage = data.required<Boolean>("alwaysPullImage").getOrThrow()
            val command = data.required<String>("command").getOrThrow()
            val args = data.required<String>("args").getOrThrow()
            val workingDir = data.required<String>("workingDir").getOrThrow()
            val ttyEnabled = data.required<Boolean>("ttyEnabled").getOrThrow()
            val resourceRequestCpu = data.required<String>("resourceRequestCpu").getOrThrow()
            val resourceRequestMemory = data.required<String>("resourceRequestMemory").getOrThrow()
            val resourceLimitCpu = data.required<String>("resourceLimitCpu").getOrThrow()
            val resourceLimitMemory = data.required<String>("resourceLimitMemory").getOrThrow()

            return Container(
                name = name,
                image = image,
                privileged = privileged,
                alwaysPullImage = alwaysPullImage,
                command = command,
                args = args,
                workingDir = workingDir,
                ttyEnabled = ttyEnabled,
                resourceRequestCpu = resourceRequestCpu,
                resourceRequestMemory = resourceRequestMemory,
                resourceLimitCpu = resourceLimitCpu,
                resourceLimitMemory = resourceLimitMemory
            )
        }
    }
}

