package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.agents.PermanentAgentConfig
import dev.rubentxu.pipeline.model.agents.SSHLauncherConfig
import dev.rubentxu.pipeline.model.agents.TemporaryAgentConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Path

@ExtendWith(SystemStubsExtension::class)
class PipelineContextTest : StringSpec({

    "Should get Pipeline Config from the credentials file" {
        val cascManager = CascManager()
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/keystore.txt").path

        val environmentVariables = mapOf(
            "SSH_KEY_PASSWORD" to "miSSHKeyPassword",
            "SSH_PRIVATE_KEY" to "miSSHPrivateKey",
            "SECRET_PASSWORD_CERT" to "miCertificatePassword",
            "SECRET_CERT_FILE_PATH" to resolveResourceFile,
            "SECRET_FILE_PATH" to resolveResourceFile,
            "SSH_PRIVATE_FILE_PATH" to resolveResourceFile,
            "SOME_USER_PASSWORD" to "userPasswordTest",
            "SECRET_TEXT" to "secretTest",
            "AWS_ACCESS_KEY" to "awsAccessKeyTest",
            "AWS_SECRET_ACCESS_KEY" to "awsSecretAccessKeyTest",

        )

        EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
            val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/credentials.yaml").path
            val testYamlPath = Path.of(resourcePath)


            val config = cascManager.resolveConfig(testYamlPath).getOrThrow()


            config.credentialsProvider?.listCredentials()?.size shouldBe 8
        }
        // Ruta al archivo YAML de prueba



    }

    "Should get Pipeline Config from the docker config file" {
        val cascManager = CascManager()

        val environmentVariables = mapOf(
            "DOCKER_AGENT_IMAGE" to "pipeline-kts/inbound-agent:latest",
        )

        EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
            val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/docker.yaml").path
            val testYamlPath = Path.of(resourcePath)
            val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

            config.clouds?.size shouldBe 1
            config.clouds?.get(0)?.docker?.name shouldBe "docker"
            config.clouds?.get(0)?.docker?.dockerHost shouldBe "unix:///var/run/docker.sock"
            config.clouds?.get(0)?.docker?.templates?.size shouldBe 1
            config.clouds?.get(0)?.docker?.templates?.get(0)?.dockerTemplateBase?.image shouldBe "pipeline-kts/inbound-agent:latest"
            config.clouds?.get(0)?.docker?.templates?.get(0)?.dockerTemplateBase?.mounts?.size shouldBe 3
            config.clouds?.get(0)?.docker?.templates?.get(0)?.dockerTemplateBase?.environmentsString shouldContain "foo=\${FOO_VAR}"
        }

    }

    "Should get Pipeline Config from the kubernetes config file" {
        val cascManager = CascManager()

        val environmentVariables = mapOf(
            "SERVER_URL" to "https://advanced-k8s-config:443",
        )

        EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
            val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/kubernetes.yaml").path
            val testYamlPath = Path.of(resourcePath)
            val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

            config.clouds?.size shouldBe 1
            config.clouds?.get(0)?.kubernetes?.name shouldBe "advanced-k8s-config"
            config.clouds?.get(0)?.kubernetes?.serverUrl shouldBe "https://advanced-k8s-config:443"
            config.clouds?.get(0)?.kubernetes?.templates?.size shouldBe 2
            config.clouds?.get(0)?.kubernetes?.templates?.get(0)?.name shouldBe "test"
            config.clouds?.get(0)?.kubernetes?.templates?.get(0)?.serviceAccount shouldBe "serviceAccount"
            config.clouds?.get(0)?.kubernetes?.templates?.get(0)?.instanceCap shouldBe 1234
            config.clouds?.get(0)?.kubernetes?.templates?.get(0)?.containers?.size shouldBe 1
            config.clouds?.get(0)?.kubernetes?.templates?.get(1)?.name shouldBe "k8s-agent"
            config.clouds?.get(0)?.kubernetes?.templates?.get(1)?.serviceAccount shouldBe ""
            config.clouds?.get(0)?.kubernetes?.templates?.get(1)?.idleMinutes shouldBe 1
            config.clouds?.get(0)?.kubernetes?.templates?.get(1)?.containers?.size shouldBe 1


        }

    }

    "Should get Pipeline Config from the shared libraries config file" {
        val cascManager = CascManager()

        val environmentVariables = mapOf(
            "LIBRARY_NAME" to "pipeline-kts",
            "LIBRARY_DEFAULT_VERSION" to "master",
            "LIBRARY_REPOSITORY" to "",
            "LIBRARY_LOAD_GROOVY_HOOK_SCRIPTS" to "true",
            "LIBRARY_ALLOW_DEFAULT_VERSION_TO_BE_OVERWRITTEN" to "false",
        )

        EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
            val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/global-libraries.yaml").path
            val testYamlPath = Path.of(resourcePath)
            val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

            config.globalLibraries.libraries.size shouldBe 1
            config.globalLibraries.libraries[0].name shouldBe "internal-pipeline-library"
            config.globalLibraries.libraries[0].retriever shouldBe GitSCMRetriever(remote="git@github.com:furry-octo-lamp-inc/pipeline-library.git", credentialsId="reimagined-parakeet-ssh")

        }


    }

    "Should get Pipeline Config from the Permanent Agent config file" {
        val cascManager = CascManager()

        val environmentVariables = mapOf(
            "PIPELINE_AGENT_HOST" to "192.168.1.100",
            "AGENT_LABEL" to "PipelineAgentTest",
        )

         EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
             val resourcePath =
                 PipelineContextTest::class.java.classLoader.getResource("casc/agent-permanent-ssh.yaml").path
             val testYamlPath = Path.of(resourcePath)
             val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

             val agentResult: PermanentAgentConfig = config.agents[0] as PermanentAgentConfig

             config.agents.size shouldBe 1
             agentResult.name shouldBe "linux-node"
             agentResult.remoteFS shouldBe "/home/build/pipeline-agent"
             agentResult.numExecutors shouldBe 2
             agentResult.labelString shouldBe "PipelineAgentTest"
             agentResult.mode shouldBe "NORMAL"
             agentResult.launcher shouldBe SSHLauncherConfig(
                    host = "192.168.1.100",
                    port = 22,
                    credentialsId = "ssh-credential-id"
                )

         }

    }

    "Should get Pipeline Config from the Temporary Agent config file" {
        val cascManager = CascManager()

        val environmentVariables = mapOf(
            "AGENT_LABEL" to "tempLabel",
        )

        EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
            val resourcePath =
                PipelineContextTest::class.java.classLoader.getResource("casc/agent-temporary.yaml").path
            val testYamlPath = Path.of(resourcePath)
            val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

            val agentResult = config.agents[0] as TemporaryAgentConfig

            config.agents.size shouldBe 1
            agentResult.name shouldBe "temp-node"
            agentResult.remoteFS shouldBe "/home/build/pipeline-agent-temp"
            agentResult.numExecutors shouldBe 1
            agentResult.labelString shouldBe "tempLabel"
            agentResult.mode shouldBe "NORMAL"
        }
    }


})
