package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.raise.result
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.propertyPath
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.NoSuchServiceError
import dev.rubentxu.pipeline.model.Res
import dev.rubentxu.pipeline.model.agents.*
import dev.rubentxu.pipeline.model.steps.EnvVars

class KubernetesAgentFactory {

    companion object : PipelineDomainFactory<List<KubernetesAgent>> {
        override val rootPath: String = "agents.clouds[*].kubernetes"

        override suspend fun create(data: PropertySet): Result<List<KubernetesAgent>> = result {
            getRootListPropertySet(data).bind()
                ?.parMap { properties ->
                    val id: IDComponent = IDComponent.create(properties.required<String>("id".propertyPath()).bind())
                    val labels: List<String> = properties.required<List<String>>("labels".propertyPath()).bind()
                    val templates = KubernetesTemplateFactory.create(properties).bind()

                    kubernetesAgent(id, properties, templates, labels).bind()
                }?: emptyList()
        }

        private fun kubernetesAgent(
            id: IDComponent,
            properties: PropertySet,
            templates: List<K8sTemplate>,
            labels: List<String>,
        ): Result<KubernetesAgent> = result {
            KubernetesAgent(
                id = id,
                name = properties.required<String>("name").bind(),
                serverUrl = properties.required<String>("serverUrl").bind(),
                serverCertificate = properties.required<String>("serverCertificate").bind(),
                skipTlsVerify = properties.required<Boolean>("skipTlsVerify").bind(),
                credentialsId = properties.required<String>("credentialsId").bind(),
                namespace = properties.required<String>("namespace").bind(),
                pipelineUrl = properties.required<String>("pipelineUrl").bind(),
                pipelineTunnel = properties.required<String>("pipelineTunnel").bind(),
                containerCapStr = properties.required<Int>("containerCapStr").bind(),
                maxRequestsPerHostStr = properties.required<Int>("maxRequestsPerHostStr").bind(),
                retentionTimeout = properties.required<Int>("retentionTimeout").bind(),
                connectTimeout = properties.required<Int>("connectTimeout").bind(),
                readTimeout = properties.required<Int>("readTimeout").bind(),
                templates = templates,
                labels = labels,
                type = "kubernetes"
            )
        }
    }


}

class KubernetesTemplateFactory {

    companion object : PipelineDomainFactory<List<K8sTemplate>> {
        override val rootPath: String = "templates"

        override suspend fun create(kubernetesProperties: PropertySet):Result<List<K8sTemplate>> = result {
            getRootListPropertySet(kubernetesProperties).bind()
                ?.parMap { templateProperties ->
                    val volumes: List<Volume> = VolumeFactory.create(templateProperties).bind()
                    val containers: List<Container> = ContainerFactory.create(templateProperties).bind()
                    val imagePullSecrets = templateProperties.required<List<String>>("imagePullSecrets[*].name").bind()


                    val envVarsMap: Map<String, String> = templateProperties.required<List<PropertySet>>("envVars").bind()
                        .map {
                            it.required<String>("key").bind() to it.required<String>("value").bind()
                        }.toMap()

                    val envVars = EnvVars(envVarsMap)
                    raise(NoSuchServiceError("envVars: $envVars"))

                    K8sTemplate(
                        name = templateProperties.required<String>("name").bind(),
                        serviceAccount = templateProperties.required<String>("serviceAccount").bind(),
                        instanceCap = templateProperties.required<Int>("instanceCap").bind(),
                        idleMinutes = templateProperties.required<Int>("idleMinutes").bind(),
                        label = templateProperties.required<String>("label").bind(),
                        showRawYaml = templateProperties.required<Boolean>("showRawYaml").bind(),
                        volumes = volumes,
                        containers = containers,
                        imagePullSecrets = imagePullSecrets,
                        envVars = envVars
                    )
                }?: emptyList()
        }

    }
}

class VolumeFactory {

    companion object : PipelineDomainFactory<List<Volume>> {
        override val rootPath: String = "volumes"


        override suspend fun create(templateProperties: PropertySet):Result<List<Volume>> {
            return createVolume(templateProperties)
        }

        private suspend fun createVolume(volumeProperties: PropertySet):Result<List<Volume>> =
            result {
                parZip(
                    { HostPathVolumeFactory.create(volumeProperties).bind() },
                    { EmptyDirVolumeFactory.create(volumeProperties).bind() },
                    { ConfigMapVolumeFactory.create(volumeProperties).bind() }
                ) { hostPathVolume, emptyDirVolume, configMapVolume ->
                    buildList {
                        addAll(hostPathVolume)
                        addAll(emptyDirVolume)
                        addAll(configMapVolume)
                    }
                }
            }
    }
}

class HostPathVolumeFactory {

    companion object : PipelineDomainFactory<List<HostPathVolume>> {
        override val rootPath: String = "volumes[*].hostPathVolume"


        override suspend fun create(data: PropertySet):Result<List<HostPathVolume>> =
            result {
                getRootListPropertySet(data).bind()
                    ?.parMap { hostPathVolumeProperties ->
                        HostPathVolume(
                            mountPath = hostPathVolumeProperties.required<String>("mountPath").bind(),
                            hostPath = hostPathVolumeProperties.required<String>("hostPath").bind()
                        )
                    }?: emptyList()
            }
    }
}

class EmptyDirVolumeFactory {
    companion object : PipelineDomainFactory<List<EmptyDirVolume>> {
        override val rootPath: String = "volumes[*].emptyDirVolume"


        override suspend fun create(data: PropertySet):Result<List<EmptyDirVolume>> =
            result {
                getRootListPropertySet(data).bind()
                    ?.parMap { emptyDirVolumeProperties ->
                        EmptyDirVolume(
                            memory = emptyDirVolumeProperties.required<Boolean>("memory").bind(),
                            mountPath = emptyDirVolumeProperties.required<String>("mountPath").bind()
                        )
                    }?: emptyList()
            }
    }
}

class ConfigMapVolumeFactory {

    companion object : PipelineDomainFactory<List<ConfigMapVolume>> {
        override val rootPath: String = "volumes[*].configMapVolume"


        override suspend fun create(data: PropertySet):Result<List<ConfigMapVolume>> =
            result {
                getRootListPropertySet(data).bind()
                    ?.parMap { configMapVolumeProperties ->
                        ConfigMapVolume(
                            configMapName = configMapVolumeProperties.required("configMapName"),
                            mountPath = configMapVolumeProperties.required("mountPath"),
                            subPath = configMapVolumeProperties.required("subPath")
                        )
                    }?: emptyList()
            }
    }
}


class ContainerFactory {

    companion object : PipelineDomainFactory<List<Container>> {
        override val rootPath: String = "containers"


        override suspend fun create(data: PropertySet):Result<List<Container>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { containerProperties ->
                        createContainer(containerProperties).bind()
                    }?: emptyList()
            }

        private fun createContainer(data: PropertySet):Result<Container> =
            result {
                Container(
                    name = data.required("name"),
                    image = data.required("image"),
                    privileged = data.required("privileged"),
                    alwaysPullImage = data.required("alwaysPullImage"),
                    command = data.required("command"),
                    args = data.required("args"),
                    workingDir = data.required("workingDir"),
                    ttyEnabled = data.required("ttyEnabled"),
                    resourceRequestCpu = data.required("resourceRequestCpu"),
                    resourceRequestMemory = data.required("resourceRequestMemory"),
                    resourceLimitCpu = data.required("resourceLimitCpu"),
                    resourceLimitMemory = data.required("resourceLimitMemory")
                )
            }
    }
}
