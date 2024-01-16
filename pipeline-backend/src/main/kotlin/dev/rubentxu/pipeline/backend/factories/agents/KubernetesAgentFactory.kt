package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.raise.Raise
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.*
import dev.rubentxu.pipeline.model.mapper.*
import dev.rubentxu.pipeline.model.steps.EnvVars


class KubernetesAgentFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<KubernetesAgent> {
        override val rootPath: PropertyPath = "kubernetes".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): KubernetesAgent {
            val kubernetesAgent = getRootPropertySet(data)

            val id: IDComponent = IDComponent.create(kubernetesAgent.required<String>("id".propertyPath()))
            val labels: List<String> = kubernetesAgent.required<List<String>>("labels".propertyPath())

            val templatesMap: List<PropertySet> = KubernetesTemplateFactory.create(data)


            return KubernetesAgent(
                id = id,
                name = kubernetesAgent.required("name".propertyPath()),
                serverUrl = data.required("kubernetes.serverUrl"),
                serverCertificate = data.required("kubernetes.serverCertificate"),
                skipTlsVerify = data.required("kubernetes.skipTlsVerify"),
                credentialsId = data.required("kubernetes.credentialsId"),
                namespace = data.required("kubernetes.namespace"),
                pipelineUrl = data.required("kubernetes.pipelineUrl"),
                pipelineTunnel = data.required("kubernetes.pipelineTunnel"),
                containerCapStr = data.required("kubernetes.containerCapStr"),
                maxRequestsPerHostStr = data.required("kubernetes.maxRequestsPerHostStr"),
                retentionTimeout = data.required("kubernetes.retentionTimeout"),
                connectTimeout = data.required("kubernetes.connectTimeout"),
                readTimeout = data.required("kubernetes.readTimeout"),
                templates = templates,
                labels = labels

            )

        }
    }
}

class KubernetesTemplateFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<Template> {
        override val rootPath: PropertyPath = "templates".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): K8sTemplate {
            val volumesMap: List<PropertySet> = data.required("volumes")

            val volumes: List<Volume> = volumesMap.map {
                return@map VolumeFactory.create(it)
            }

            val containersMap: List<PropertySet> = data.required("containers")


            val containers: List<Container> = containersMap.map {
                return@map ContainerFactory.create(it)
            }

            val imagePullSecretsMap: List<PropertySet> = data.required("imagePullSecrets")


            val imagePullSecrets: List<ImagePullSecret> = imagePullSecretsMap.map {
                return@map ImagePullSecretFactory.create(it)
            }

            val envVarsListMap: List<PropertySet> = data.required("envVars")


            val envVarsMap = envVarsListMap
                .map { it.mapValues { it.value.toString() } }
                .fold(emptyMap<String, String>()) { acc, map ->
                    acc + map
                }

            val envVars = EnvVars(envVarsMap)

            return K8sTemplate(
                name = data.required("name"),
                serviceAccount = data.required("serviceAccount"),
                instanceCap = data.required("instanceCap"),
                idleMinutes = data.required("idleMinutes"),
                label = data.required("label"),
                showRawYaml = data.required("showRawYaml"),
                volumes = volumes,
                containers = containers,
                imagePullSecrets = imagePullSecrets,
                envVars = envVars
            )
        }
    }
}

class VolumeFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<Volume> {
        override val rootPath: PropertyPath = "volumes".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): Volume {
            val hostPathVolumeMap: PropertySet = data.required("hostPathVolume")
            val emptyDirVolumeMap: PropertySet = data.required("emptyDirVolume")
            val configMapVolumeMap: PropertySet = data.required("configMapVolume")

            return Volume(
                hostPathVolume = HostPathVolumeFactory.create(hostPathVolumeMap),
                emptyDirVolume = EmptyDirVolumeFactory.create(emptyDirVolumeMap),
                configMapVolume = ConfigMapVolumeFactory.create(configMapVolumeMap)
            )
        }
    }
}

class HostPathVolumeFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<HostPathVolume> {
        override val rootPath: PropertyPath = "hostPathVolume".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): HostPathVolume {
            return HostPathVolume(
                mountPath = data.required("mountPath"),
                hostPath = data.required("hostPath")
            )
        }
    }
}

class EmptyDirVolumeFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<EmptyDirVolume?> {
        override val rootPath: PropertyPath = "emptyDirVolume".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): EmptyDirVolume? {
            if (data.isEmpty()) return null
            return EmptyDirVolume(
                memory = data.required("memory"),
                mountPath = data.required("mountPath")
            )
        }
    }
}

class ConfigMapVolumeFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<ConfigMapVolume?> {
        override val rootPath: PropertyPath = "configMapVolume".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): ConfigMapVolume? {
            if (data.isEmpty()) return null
            return ConfigMapVolume(
                configMapName = data.required("configMapName"),
                mountPath = data.required("mountPath"),
                subPath = data.required("subPath")
            )
        }
    }
}

class ContainerFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<Container> {
        override val rootPath: PropertyPath = "containers".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): Container {
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

class ImagePullSecretFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<ImagePullSecret> {
        override val rootPath: String = "imagePullSecrets"


        override suspend fun create(data: PropertySet): ImagePullSecret {
            return ImagePullSecret(
                name = data.required("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("name"))
            )
        }
    }
}
