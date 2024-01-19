package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.raise.Raise
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.*
import dev.rubentxu.pipeline.model.mapper.*
import dev.rubentxu.pipeline.model.steps.EnvVars


class KubernetesAgentFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<KubernetesAgent>> {
        override val rootPath: PropertyPath = "agents.clouds[*].kubernetes".propertyPath()

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<KubernetesAgent> {
            return getRootListPropertySet(data)
                .map { kubernetesProperties ->
                    createKubernetesAgent(kubernetesProperties)
                }
        }

        private suspend fun createKubernetesAgent(            // (
            properties: PropertySet,
        ): KubernetesAgent {
            val id: IDComponent = IDComponent.create(properties.required<String>("id".propertyPath()))
            val labels: List<String> = properties.required<List<String>>("labels".propertyPath())
            val templates= KubernetesTemplateFactory.create(properties)


            return KubernetesAgent(
                id = id,
                name = properties.required("kubernetes.name", rootPath),
                serverUrl = properties.required("kubernetes.serverUrl", rootPath),
                serverCertificate = properties.required("kubernetes.serverCertificate", rootPath),
                skipTlsVerify = properties.required("kubernetes.skipTlsVerify", rootPath),
                credentialsId = properties.required("kubernetes.credentialsId", rootPath),
                namespace = properties.required("kubernetes.namespace", rootPath),
                pipelineUrl = properties.required("kubernetes.pipelineUrl", rootPath),
                pipelineTunnel = properties.required("kubernetes.pipelineTunnel", rootPath),
                containerCapStr = properties.required("kubernetes.containerCapStr", rootPath),
                maxRequestsPerHostStr = properties.required("kubernetes.maxRequestsPerHostStr", rootPath),
                retentionTimeout = properties.required("kubernetes.retentionTimeout", rootPath),
                connectTimeout = properties.required("kubernetes.connectTimeout", rootPath),
                readTimeout = properties.required("kubernetes.readTimeout", rootPath),
                templates = templates,
                labels = labels

            )
        }
    }
}

class KubernetesTemplateFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<K8sTemplate>> {
        override val rootPath: PropertyPath = "agents.clouds[*].kubernetes.templates".propertyPath()

        context(Raise<PropertiesError>)
        override suspend fun create(kubernetesProperties: PropertySet): List<K8sTemplate> {
            val templates =  kubernetesProperties
                .required<List<PropertySet>>("kubernetes.templates", rootPath)
                .map { templateProperties ->
                    createTemplate(templateProperties)
                }

            return templates
        }

        context(Raise<PropertiesError>)
        private fun createTemplate(templateProperties: PropertySet): K8sTemplate {
            val volumes: List<Volume> = VolumeFactory.create(templateProperties)

            val volumes: List<Volume> =

            val containersMap: List<PropertySet> = templateProperties.required("containers")


            val containers: List<Container> = containersMap.map {
                return@map ContainerFactory.create(it)
            }

            val imagePullSecretsMap: List<PropertySet> = templateProperties.required("imagePullSecrets")


            val imagePullSecrets: List<ImagePullSecret> = imagePullSecretsMap.map {
                return@map ImagePullSecretFactory.create(it)
            }

            val envVarsListMap: List<PropertySet> = templateProperties.required("envVars")


            val envVarsMap = envVarsListMap
                .map { it.mapValues { it.value.toString() } }
                .fold(emptyMap<String, String>()) { acc, map ->
                    acc + map
                }

            val envVars = EnvVars(envVarsMap)

            return K8sTemplate(
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

class VolumeFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<Volume>> {
        override val rootPath: PropertyPath = "templates[*].volumes".propertyPath()

        context(Raise<PropertiesError>)
        override suspend fun create(templateProperties: PropertySet): List<Volume> {
            return getRootListPropertySet(templateProperties)
                .map { volumeProperties ->
                    createVolume(volumeProperties)
                }
        }



        private fun createVolume(volumeProperties: PropertySet): Volume {
            val hostPathVolumeMap: PropertySet = volumeProperties.required("hostPathVolume")
            val emptyDirVolumeMap: PropertySet = volumeProperties.required("emptyDirVolume")
            val configMapVolumeMap: PropertySet = volumeProperties.required("configMapVolume")

            return Volume(
                hostPathVolume = HostPathVolumeFactory.create(hostPathVolumeMap),
                emptyDirVolume = EmptyDirVolumeFactory.create(emptyDirVolumeMap),
                configMapVolume = ConfigMapVolumeFactory.create(configMapVolumeMap)
            )
        }
    }
}

class HostPathVolumeFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<HostPathVolume> {
        override val rootPath: PropertyPath = "hostPathVolume".propertyPath()

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): HostPathVolume {
            return HostPathVolume(
                mountPath = data.required("mountPath"),
                hostPath = data.required("hostPath")
            )
        }
    }
}

class EmptyDirVolumeFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<EmptyDirVolume?> {
        override val rootPath: PropertyPath = "emptyDirVolume".propertyPath()

        context(Raise<PropertiesError>)
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
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<ConfigMapVolume?> {
        override val rootPath: PropertyPath = "configMapVolume".propertyPath()

        context(Raise<PropertiesError>)
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
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<Container> {
        override val rootPath: PropertyPath = "containers".propertyPath()

        context(Raise<PropertiesError>)
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

    context(Raise<PropertiesError>)
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
