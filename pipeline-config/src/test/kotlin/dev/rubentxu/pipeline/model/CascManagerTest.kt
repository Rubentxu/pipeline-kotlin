package dev.rubentxu.pipeline.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.scopes.StringSpecScope
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.extension.ExtendWith
import pipeline.kotlin.extensions.LookupException
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Path



@ExtendWith(SystemStubsExtension::class)
class CascManagerTest : StringSpec({
    "resolveConfig should correctly deserialize YAML secrets with basic SSH Private Key to PipelineConfig" {
        val environmentVariables = mapOf(
            "SSH_KEY_PASSWORD" to "miSSHKeyPassword",
            "SSH_PRIVATE_KEY" to "miSSHPrivateKey"
        )
        val cascFileName = "casc/testData/credentials-basicSSH_Private_Key.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as BasicSSHUserPrivateKey

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "ssh_with_passphrase_provided"
        credential?.username shouldBe "ssh_root"
        credential?.passphrase shouldBe "miSSHKeyPassword"
        credential?.privateKey shouldBe "miSSHPrivateKey"

    }

    "resolveConfig should correctly deserialize YAML secrets with basic SSH Private Key with file secret to PipelineConfig" {
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/ssh_private_key.txt").path

        val environmentVariables = mapOf(
            "SSH_KEY_PASSWORD" to "miSSHKeyPassword",
            "SSH_PRIVATE_FILE_PATH" to resolveResourceFile
        )
        val cascFileName = "casc/testData/credentials-basicSSH_Private_Key_with_file.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as BasicSSHUserPrivateKey

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "ssh_with_passphrase_provided_via_file"
        credential?.scope shouldBe "SYSTEM"
        credential?.username shouldBe "ssh_root"
        credential?.passphrase shouldBe "miSSHKeyPassword"
        credential?.privateKey shouldBe "miSSHPrivateKeyInFile"

    }

    "resolveConfig should correctly deserialize YAML secrets Certificate to PipelineConfig" {
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/keystore.txt").path

        val environmentVariables = mapOf(
            "SECRET_PASSWORD_CERT" to "miCertificatePassword",
            "SECRET_CERT_FILE_PATH" to resolveResourceFile
        )
        val cascFileName = "casc/testData/credentials-certificate.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as CertificateCredential

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "secret-certificate"
        credential?.scope shouldBe "GLOBAL"
        credential?.password shouldBe "miCertificatePassword"
        credential?.keyStore shouldBe java.util.Base64.getEncoder().encodeToString("miKeyStoreInFile".toByteArray())
        credential?.description shouldBe "my secret cert"

    }

    "resolve should fail deserialize YAML secrets Certificate to PipelineConfig" {

        val environmentVariables = mapOf(
            "SECRET_PASSWORD_CERT" to "miCertificatePassword",
            "SECRET_CERT_FILE_PATH" to "casc/testData/keystore-not-exist.txt"
        )
        val cascFileName = "casc/testData/credentials-certificate.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))


        val exception = shouldThrow<LookupException> {
            testConfig(environmentVariables, cascFileName, result).getOrThrow()
        }
        exception.message shouldBe "Error in file base64 lookup: NoSuchFileException casc/testData/keystore-not-exist.txt"

    }

    "resolve should correctly deserialize YAML file secret to PipelineConfig" {
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/secret.txt").path

        val environmentVariables = mapOf(
            "SECRET_FILE_PATH" to resolveResourceFile
        )
        val cascFileName = "casc/testData/credentials-file.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as FileCredential

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "secret-file"
        credential?.scope shouldBe "GLOBAL"
        credential?.fileName shouldBe "mysecretfile.txt"
        credential?.secretBytes shouldBe "bWlLZXlTdG9yZUluRmlsZQ=="
        credential?.description shouldBe ""

    }

    "resolve should fail deserialize YAML file secret to PipelineConfig" {

        val environmentVariables = mapOf(
            "SECRET_FILE_PATH" to "casc/testData/secret-not-exist.txt"
        )
        val cascFileName = "casc/testData/credentials-file.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        val exception = shouldThrow<LookupException> {
            testConfig(environmentVariables, cascFileName, result).getOrThrow()
        }
        exception.message shouldBe "Error in file string lookup NoSuchFileException casc/testData/secret-not-exist.txt"
    }

    "resolve should correctly deserialize YAML file64 secret to PipelineConfig" {
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/secret.txt").path

        val environmentVariables = mapOf(
            "SECRET_FILE_PATH" to resolveResourceFile
        )
        val cascFileName = "casc/testData/credentials-file-with-readFileBase64.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as FileCredential

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "secret-file_via_binary_file"
        credential?.scope shouldBe "GLOBAL"
        credential?.fileName shouldBe "mysecretfile.txt"
        credential?.secretBytes shouldBe "bWlLZXlTdG9yZUluRmlsZQ=="
        credential?.description shouldBe ""

    }

    "resolve should correctly deserialize YAML aws secret to PipelineConfig" {

        val environmentVariables = mapOf(
            "AWS_ACCESS_KEY" to "awsAccessKeyTest",
            "AWS_SECRET_ACCESS_KEY" to "awsSecretAccessKeyTest"
        )
        val cascFileName = "casc/testData/credentials-aws.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as AwsCredential

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "AWS"
        credential?.scope shouldBe "GLOBAL"
        credential?.accessKey shouldBe "awsAccessKeyTest"
        credential?.secretKey shouldBe "awsSecretAccessKeyTest"
        credential?.description shouldBe "AWS Credentials"

    }

    "resolve should correctly deserialize YAML string secret to PipelineConfig" {
        val environmentVariables = mapOf(
            "SECRET_TEXT" to "secretTest",
        )
        val cascFileName = "casc/testData/credentials-string.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as StringCredential

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "secret-text"
        credential?.scope shouldBe "GLOBAL"
        credential?.secret shouldBe "secretTest"
        credential?.description shouldBe "Secret Text"

    }

    "resolve should correctly deserialize YAML string secret with default value to PipelineConfig" {
        val environmentVariables = mapOf("GREETING" to "Hello")

        val cascFileName = "casc/testData/credentials-string-with-default-value.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as StringCredential

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "secret-text"
        credential?.scope shouldBe "GLOBAL"
        credential?.secret shouldBe "defaultSecretText"
        credential?.description shouldBe "Secret Text"

    }

    "resolve should correctly deserialize YAML string password secret to PipelineConfig" {
        val environmentVariables = mapOf(
            "SOME_USER_PASSWORD" to "userPasswordTest",
        )
        val cascFileName = "casc/testData/credentials-usernamePassword.yaml"
        var result: Result<PipelineConfig> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = result.getOrThrow().credentials?.credentials?.get(0) as UsernamePassword

        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 1
        credential?.id shouldBe "username"
        credential?.scope shouldBe "GLOBAL"
        credential?.username shouldBe "some-user"
        credential?.password shouldBe "userPasswordTest"
        credential?.description shouldBe "Username/Password Credentials for some-user"
    }



})

private fun StringSpecScope.testConfig(
    environmentVariables: Map<String, String>,
    cascFileName: String,
    result: Result<PipelineConfig>
): Result<PipelineConfig> {
    var result1 = result
    EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
        val cascManager = CascManager()
        // Ruta al archivo YAML de prueba
        val resourcePath = this::class.java.classLoader.getResource(cascFileName).path
        val testYamlPath = Path.of(resourcePath)

        // Ejecutar resolveConfig
        result1 = cascManager.resolveConfig(testYamlPath)
    }
    return result1
}
