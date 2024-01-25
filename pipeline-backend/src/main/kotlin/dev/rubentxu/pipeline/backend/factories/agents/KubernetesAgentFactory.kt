package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.propertyPath
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PropertiesError
import dev.rubentxu.pipeline.model.Res
import dev.rubentxu.pipeline.model.agents.*
import dev.rubentxu.pipeline.model.steps.EnvVars

class KubernetesAgentFactory {

    companion object : PipelineDomainFactory<Res<List<KubernetesAgent>>> {
        override val rootPath: String = "agents.clouds[*].kubernetes"

        override suspend fun create(data: PropertySet): Res<List<KubernetesAgent>> = either {
            getRootListPropertySet(data)
                .parMap { properties ->
                    val id: IDComponent = IDComponent.create(properties.required<String>("id".propertyPath()))
                    val labels: List<String> = properties.required<List<String>>("labels".propertyPath())
                    val templates = KubernetesTemplateFactory.create(properties)


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


}

class KubernetesTemplateFactory {

    companion object : PipelineDomainFactory<Res<List<K8sTemplate>>> {
        override val rootPath: String = "templates"

        context(Raise<PropertiesError>)
        override suspend fun create(kubernetesProperties: PropertySet): Res<List<K8sTemplate>> = either {
            getRootListPropertySet(kubernetesProperties)
                .parMap { templateProperties ->
                    val volumes: List<Volume> = VolumeFactory.create(templateProperties)
                    val containers: List<Container> = ContainerFactory.create(templateProperties)
                    val imagePullSecrets: List<String> = templateProperties.required("imagePullSecrets[*].name")


                    val envVarsMap: Map<String, String> = templateProperties.required<List<PropertySet>>("envVars")
                        .map {
                            it.required<String>("key") to it.required<String>("value")
                        }.toMap()

                    val envVars = EnvVars(envVarsMap)

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
                }
        }

    }
}

class VolumeFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<Volume>> {
        override val rootPath: String = "volumes"

        context(Raise<PropertiesError>)
        override suspend fun create(templateProperties: PropertySet): List<Volume> {
            return createVolume(templateProperties).bind()
        }

        private suspend fun createVolume(volumeProperties: PropertySet): Either<PropertiesError, List<Volume>> =
            either {
                parZip(
                    { HostPathVolumeFactory.create(volumeProperties) },
                    { EmptyDirVolumeFactory.create(volumeProperties) },
                    { ConfigMapVolumeFactory.create(volumeProperties) }
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
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<HostPathVolume>> {
        override val rootPath: String = "volumes[*].hostPathVolume"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<HostPathVolume> {
            return getRootListPropertySet(data)
                .parMap { hostPathVolumeProperties ->
                    HostPathVolume(
                        mountPath = hostPathVolumeProperties.required("mountPath"),
                        hostPath = hostPathVolumeProperties.required("hostPath")
                    )
                }
        }
    }
}

class EmptyDirVolumeFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<EmptyDirVolume>> {
        override val rootPath: String = "volumes[*].emptyDirVolume"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<EmptyDirVolume> {
            return getRootListPropertySet(data)
                .parMap { emptyDirVolumeProperties ->
                    EmptyDirVolume(
                        memory = emptyDirVolumeProperties.required("memory"),
                        mountPath = emptyDirVolumeProperties.required("mountPath")
                    )
                }
        }
    }
}

class ConfigMapVolumeFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<ConfigMapVolume>> {
        override val rootPath: String = "volumes[*].configMapVolume"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<ConfigMapVolume> {
            return getRootListPropertySet(data)
                .parMap { configMapVolumeProperties ->
                    ConfigMapVolume(
                        configMapName = configMapVolumeProperties.required("configMapName"),
                        mountPath = configMapVolumeProperties.required("mountPath"),
                        subPath = configMapVolumeProperties.required("subPath")
                    )
                }
        }
    }
}


class ContainerFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<Container>> {
        override val rootPath: String = "containers"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<Container> {
            return getRootListPropertySet(data)
                .parMap { containerProperties ->
                    createContainer(containerProperties)
                }
        }

        private fun createContainer(data: PropertySet): Container {
            return Container(
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
