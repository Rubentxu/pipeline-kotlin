package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.NonEmptyList
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.*
import dev.rubentxu.pipeline.model.steps.EnvVars
import dev.rubentxu.pipeline.model.validations.validateAndGet
import arrow.core.raise.Raise
import dev.rubentxu.pipeline.model.mapper.*
import dev.rubentxu.pipeline.model.mapper.ValidationError


class KubernetesAgentFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<KubernetesAgent> {
        override val rootPath: PropertyPath = "kubernetes".propertyPath()

        context(Raise<NonEmptyList<ValidationError>>)
        override suspend fun create(data: PropertySet): KubernetesAgent {
            val kubernetesAgent = getRootPropertySet(data)

            val id: IDComponent = IDComponent.create(kubernetesAgent.required<String>("id".propertyPath()))

            val labels: List<String> = kubernetesAgent.required<List<String>>("labels".propertyPath())

            val templatesMap: List<Map<String, Any>> = kubernetesAgent.required<List<Map<String, Any>>>("templates".propertyPath())

            val templates: List<K8sTemplate> = templatesMap.map {
                return@map KubernetesTemplateFactory.create(it)
            }
            return KubernetesAgent(
                id = id,
                name = kubernetesAgent.required("name".propertyPath()),
                serverUrl = data.validateAndGet("kubernetes.serverUrl").isString()
                    .throwIfInvalid("serverUrl is required in KubernetesConfig"),
                serverCertificate = data.validateAndGet("kubernetes.serverCertificate").isString()
                    .throwIfInvalid("serverCertificate is required in KubernetesConfig"),
                skipTlsVerify = data.validateAndGet("kubernetes.skipTlsVerify").isBoolean()
                    .defaultValueIfInvalid(false) as Boolean,
                credentialsId = data.validateAndGet("kubernetes.credentialsId").isString()
                    .throwIfInvalid("credentialsId is required in KubernetesConfig"),
                namespace = data.validateAndGet("kubernetes.namespace").isString()
                    .throwIfInvalid("namespace is required in KubernetesConfig"),
                pipelineUrl = data.validateAndGet("kubernetes.pipelineUrl").isString()
                    .throwIfInvalid("pipelineUrl is required in KubernetesConfig"),
                pipelineTunnel = data.validateAndGet("kubernetes.pipelineTunnel").isString()
                    .throwIfInvalid("pipelineTunnel is required in KubernetesConfig"),
                containerCapStr = data.validateAndGet("kubernetes.containerCapStr").isNumber()
                    .defaultValueIfInvalid(10) as Int,
                maxRequestsPerHostStr = data.validateAndGet("kubernetes.maxRequestsPerHostStr").isNumber()
                    .defaultValueIfInvalid(32) as Int,
                retentionTimeout = data.validateAndGet("kubernetes.retentionTimeout").isNumber()
                    .defaultValueIfInvalid(5) as Int,
                connectTimeout = data.validateAndGet("kubernetes.connectTimeout").isNumber()
                    .defaultValueIfInvalid(5) as Int,
                readTimeout = data.validateAndGet("kubernetes.readTimeout").isNumber().defaultValueIfInvalid(15) as Int,
                templates = templates,
                labels = labels

            )

        }
    }
}

class KubernetesTemplateFactory {
    companion object : PipelineDomainFactory<Template> {
        override val rootPath: String = "kubernetes.templates"
        override val instanceName: String = "Template"

        override suspend fun create(data: PropertySet): K8sTemplate {
            val volumesMap: List<Map<String, Any>> = data.validateAndGet("volumes")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val volumes: List<Volume> = volumesMap.map {
                return@map VolumeFactory.create(it)
            }

            val containersMap: List<Map<String, Any>> = data.validateAndGet("containers")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val containers: List<Container> = containersMap.map {
                return@map ContainerFactory.create(it)
            }

            val imagePullSecretsMap: List<Map<String, Any>> = data.validateAndGet("imagePullSecrets")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val imagePullSecrets: List<ImagePullSecret> = imagePullSecretsMap.map {
                return@map ImagePullSecretFactory.create(it)
            }

            val envVarsListMap: List<Map<String, Any>> = data.validateAndGet("envVars")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, String>>()) as List<Map<String, Any>>

            val envVarsMap = envVarsListMap
                .map { it.mapValues { it.value.toString() } }
                .fold(emptyMap<String, String>()) { acc, map ->
                    acc + map
                }

            val envVars = EnvVars(envVarsMap)

            return K8sTemplate(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Template"),
                serviceAccount = data.validateAndGet("serviceAccount").isString().defaultValueIfInvalid("") as String,
                instanceCap = data.validateAndGet("instanceCap").isNumber().defaultValueIfInvalid(10) as Int,
                idleMinutes = data.validateAndGet("idleMinutes").isNumber().defaultValueIfInvalid(5) as Int,
                label = data.validateAndGet("label").isString().defaultValueIfInvalid("") as String,
                showRawYaml = data.validateAndGet("showRawYaml").isBoolean().defaultValueIfInvalid(false) as Boolean,
                volumes = volumes,
                containers = containers,
                imagePullSecrets = imagePullSecrets,
                envVars = envVars
            )
        }
    }
}

class VolumeFactory {
    companion object : PipelineDomainFactory<Volume> {
        override val rootPath: String = "volumes"
        override val instanceName: String = "Volume"

        override suspend fun create(data: PropertySet): Volume {
            val hostPathVolumeMap: Map<String, Any> = data.validateAndGet("hostPathVolume")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

            val emptyDirVolumeMap: Map<String, Any> = data.validateAndGet("emptyDirVolume")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

            val configMapVolumeMap: Map<String, Any> = data.validateAndGet("configMapVolume")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

            return Volume(
                hostPathVolume = HostPathVolumeFactory.create(hostPathVolumeMap),
                emptyDirVolume = EmptyDirVolumeFactory.create(emptyDirVolumeMap),
                configMapVolume = ConfigMapVolumeFactory.create(configMapVolumeMap)
            )
        }
    }
}

class HostPathVolumeFactory {
    companion object : PipelineDomainFactory<HostPathVolume> {
        override val rootPath: String = "hostPathVolume"
        override val instanceName: String = "HostPathVolume"

        override suspend fun create(data: PropertySet): HostPathVolume {
            return HostPathVolume(
                mountPath = data.validateAndGet("mountPath").isString()
                    .throwIfInvalid("mountPath is required in HostPathVolume"),
                hostPath = data.validateAndGet("hostPath").isString()
                    .throwIfInvalid("hostPath is required in HostPathVolume")
            )
        }
    }
}

class EmptyDirVolumeFactory {
    companion object : PipelineDomainFactory<EmptyDirVolume?> {
        override val rootPath: String = "emptyDirVolume"
        override val instanceName: String = "EmptyDirVolume"

        override suspend fun create(data: PropertySet): EmptyDirVolume? {
            if (data.isEmpty()) return null
            return EmptyDirVolume(
                memory = data.validateAndGet("memory").isBoolean().defaultValueIfInvalid(false) as Boolean,
                mountPath = data.validateAndGet("mountPath").isString()
                    .throwIfInvalid("mountPath is required in EmptyDirVolume")
            )
        }
    }
}

class ConfigMapVolumeFactory {
    companion object : PipelineDomainFactory<ConfigMapVolume?> {
        override val rootPath: String = "configMapVolume"
        override val instanceName: String = "ConfigMapVolume"

        override suspend fun create(data: PropertySet): ConfigMapVolume? {
            if (data.isEmpty()) return null
            return ConfigMapVolume(
                configMapName = data.validateAndGet("configMapName").isString()
                    .throwIfInvalid("configMapName is required in ConfigMapVolume"),
                mountPath = data.validateAndGet("mountPath").isString()
                    .throwIfInvalid("mountPath is required in ConfigMapVolume"),
                subPath = data.validateAndGet("subPath").isString()
                    .throwIfInvalid("subPath is required in ConfigMapVolume")
            )
        }
    }
}

class ContainerFactory {
    companion object : PipelineDomainFactory<Container> {
        override val rootPath: String = "containers"
        override val instanceName: String = "Container"

        override suspend fun create(data: PropertySet): Container {
            return Container(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Container"),
                image = data.validateAndGet("image").isString().throwIfInvalid("image is required in Container"),
                privileged = data.validateAndGet("privileged").isBoolean().defaultValueIfInvalid(false) as Boolean,
                alwaysPullImage = data.validateAndGet("alwaysPullImage").isBoolean()
                    .defaultValueIfInvalid(false) as Boolean,
                command = data.validateAndGet("command").isString().defaultValueIfInvalid("") as String,
                args = data.validateAndGet("args").isString().defaultValueIfInvalid("") as String,
                workingDir = data.validateAndGet("workingDir").isString().defaultValueIfInvalid("") as String,
                ttyEnabled = data.validateAndGet("ttyEnabled").isBoolean().defaultValueIfInvalid(false) as Boolean,
                resourceRequestCpu = data.validateAndGet("resourceRequestCpu").isString()
                    .defaultValueIfInvalid("") as String,
                resourceRequestMemory = data.validateAndGet("resourceRequestMemory").isString()
                    .defaultValueIfInvalid("") as String,
                resourceLimitCpu = data.validateAndGet("resourceLimitCpu").isString()
                    .defaultValueIfInvalid("") as String,
                resourceLimitMemory = data.validateAndGet("resourceLimitMemory").isString()
                    .defaultValueIfInvalid("") as String
            )
        }
    }
}

class ImagePullSecretFactory {
    companion object : PipelineDomainFactory<ImagePullSecret> {
        override val rootPath: String = "imagePullSecrets"
        override val instanceName: String = "ImagePullSecret"

        override suspend fun create(data: PropertySet): ImagePullSecret {
            return ImagePullSecret(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("name"))
            )
        }
    }
}
