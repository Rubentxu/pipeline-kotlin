package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.raise.either
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

        override suspend fun create(data: PropertySet): Res<List<KubernetesAgent>> = either {
            getRootListPropertySet(data)
                ?.parMap { properties ->
                    val id: IDComponent = IDComponent.create(properties.required<String>("id".propertyPath()))
                    val labels: List<String> = properties.required<List<String>>("labels".propertyPath())
                    val templates = KubernetesTemplateFactory.create(properties).bind()

                    kubernetesAgent(id, properties, templates, labels).bind()
                }?: emptyList()
        }

        private fun kubernetesAgent(
            id: IDComponent,
            properties: PropertySet,
            templates: List<K8sTemplate>,
            labels: List<String>,
        ) = either {
            KubernetesAgent(
                id = id,
                name = properties.required("name"),
                serverUrl = properties.required("serverUrl"),
                serverCertificate = properties.required("serverCertificate"),
                skipTlsVerify = properties.required("skipTlsVerify"),
                credentialsId = properties.required("credentialsId"),
                namespace = properties.required("namespace"),
                pipelineUrl = properties.required("pipelineUrl"),
                pipelineTunnel = properties.required("pipelineTunnel"),
                containerCapStr = properties.required("containerCapStr"),
                maxRequestsPerHostStr = properties.required("maxRequestsPerHostStr"),
                retentionTimeout = properties.required("retentionTimeout"),
                connectTimeout = properties.required("connectTimeout"),
                readTimeout = properties.required("readTimeout"),
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

        override suspend fun create(kubernetesProperties: PropertySet): Res<List<K8sTemplate>> = either {
            getRootListPropertySet(kubernetesProperties)
                ?.parMap { templateProperties ->
                    val volumes: List<Volume> = VolumeFactory.create(templateProperties).bind()
                    val containers: List<Container> = ContainerFactory.create(templateProperties).bind()
                    val imagePullSecrets: List<String> = templateProperties.required("imagePullSecrets[*].name")


                    val envVarsMap: Map<String, String> = templateProperties.required<List<PropertySet>>("envVars")
                        .map {
                            it.required<String>("key") to it.required<String>("value")
                        }.toMap()

                    val envVars = EnvVars(envVarsMap)
                    raise(NoSuchServiceError("envVars: $envVars"))

                    K8sTemplate(
                        name = templateProperties.required("name"),
                        serviceAccount = templateProperties.required("serviceAccount"),
                        instanceCap = templateProperties.required("instanceCap"),
                        idleMinutes = templateProperties.required("idleMinutes"),
                        label = templateProperties.required("label"),
                        showRawYaml = templateProperties.required("showRawYaml"),
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


        override suspend fun create(templateProperties: PropertySet): Res<List<Volume>> {
            return createVolume(templateProperties)
        }

        private suspend fun createVolume(volumeProperties: PropertySet): Res<List<Volume>> =
            either {
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


        override suspend fun create(data: PropertySet): Res<List<HostPathVolume>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { hostPathVolumeProperties ->
                        HostPathVolume(
                            mountPath = hostPathVolumeProperties.required("mountPath"),
                            hostPath = hostPathVolumeProperties.required("hostPath")
                        )
                    }?: emptyList()
            }
    }
}

class EmptyDirVolumeFactory {
    companion object : PipelineDomainFactory<List<EmptyDirVolume>> {
        override val rootPath: String = "volumes[*].emptyDirVolume"


        override suspend fun create(data: PropertySet): Res<List<EmptyDirVolume>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { emptyDirVolumeProperties ->
                        EmptyDirVolume(
                            memory = emptyDirVolumeProperties.required("memory"),
                            mountPath = emptyDirVolumeProperties.required("mountPath")
                        )
                    }?: emptyList()
            }
    }
}

class ConfigMapVolumeFactory {

    companion object : PipelineDomainFactory<List<ConfigMapVolume>> {
        override val rootPath: String = "volumes[*].configMapVolume"


        override suspend fun create(data: PropertySet): Res<List<ConfigMapVolume>> =
            either {
                getRootListPropertySet(data)
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


        override suspend fun create(data: PropertySet): Res<List<Container>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { containerProperties ->
                        createContainer(containerProperties).bind()
                    }?: emptyList()
            }

        private fun createContainer(data: PropertySet): Res<Container> =
            either {
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
