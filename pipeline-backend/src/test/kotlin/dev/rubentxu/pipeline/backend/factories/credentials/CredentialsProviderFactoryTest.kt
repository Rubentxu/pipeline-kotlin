package dev.rubentxu.pipeline.backend.factories.credentials

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.effect
import arrow.core.raise.either
import dev.rubentxu.pipeline.backend.cdi.CascManager
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.credentials.*
import dev.rubentxu.pipeline.model.mapper.PropertiesError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.scopes.StringSpecScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import pipeline.kotlin.extensions.LookupException
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import java.nio.file.Path

class CredentialsProviderFactoryTest : StringSpec({
    "resolveConfig should correctly deserialize YAML secrets with basic SSH Private Key to PipelineConfig" {
        val environmentVariables = mapOf(
            "SSH_KEY_PASSWORD" to "miSSHKeyPassword",
            "SSH_PRIVATE_KEY" to "miSSHPrivateKey"
        )
        val idExpected = IDComponent.create("ssh_with_passphrase_provided")
        val cascFileName = "casc/testData/credentials-basicSSH_Private_Key.yaml"

        val credentialsProvider = testConfig(environmentVariables, cascFileName)
        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as BasicSSHUserPrivateKey

        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe IDComponent.create("ssh_with_passphrase_provided")
        credential?.username shouldBe "ssh_root"
        credential?.passphrase shouldBe "miSSHKeyPassword"
        credential?.privateKey shouldBe "miSSHPrivateKey"

    }

    "resolveConfig should correctly deserialize YAML secrets with basic SSH Private Key with file secret to PipelineConfig" {
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/ssh_private_key.txt").path
        val idExpected = IDComponent.create("ssh_with_passphrase_provided_via_file")
        val environmentVariables = mapOf(
            "SSH_KEY_PASSWORD" to "miSSHKeyPassword",
            "SSH_PRIVATE_FILE_PATH" to resolveResourceFile
        )
        val cascFileName = "casc/testData/credentials-basicSSH_Private_Key_with_file.yaml"
        val credentialsProvider = testConfig(environmentVariables, cascFileName)

        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as BasicSSHUserPrivateKey

        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe idExpected
        credential?.scope shouldBe "SYSTEM"
        credential?.username shouldBe "ssh_root"
        credential?.passphrase shouldBe "miSSHKeyPassword"
        credential?.privateKey shouldBe "miSSHPrivateKeyInFile"

    }

    "resolveConfig should correctly deserialize YAML secrets Certificate to PipelineConfig" {
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/keystore.txt").path
        val idExpected = IDComponent.create("secret-certificate")
        val environmentVariables = mapOf(
            "SECRET_PASSWORD_CERT" to "miCertificatePassword",
            "SECRET_CERT_FILE_PATH" to resolveResourceFile
        )
        val cascFileName = "casc/testData/credentials-certificate.yaml"
        val credentialsProvider = testConfig(environmentVariables, cascFileName)

        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as CertificateCredentials

        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe idExpected
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


        val exception = shouldThrow<LookupException> {
            testConfig(environmentVariables, cascFileName)
        }
        exception.message shouldBe "Error in file base64 lookup: NoSuchFileException casc/testData/keystore-not-exist.txt"

    }

    "resolve should correctly deserialize YAML file secret to PipelineConfig" {
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/secret.txt").path
        val idExpected = IDComponent.create("secret-file")
        val environmentVariables = mapOf(
            "SECRET_FILE_PATH" to resolveResourceFile
        )
        val cascFileName = "casc/testData/credentials-file.yaml"
        val credentialsProvider = testConfig(environmentVariables, cascFileName)
        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as FileCredentials

        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe idExpected
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

        val exception = shouldThrow<LookupException> {
            testConfig(environmentVariables, cascFileName)
        }
        exception.message shouldBe "Error in file string lookup NoSuchFileException casc/testData/secret-not-exist.txt"
    }

    "resolve should correctly deserialize YAML file64 secret to PipelineConfig" {
        val idExpected = IDComponent.create("secret-file_via_binary_file")
        val resolveResourceFile = this::class.java.classLoader.getResource("casc/testData/secret.txt").path

        val environmentVariables = mapOf(
            "SECRET_FILE_PATH" to resolveResourceFile
        )
        val cascFileName = "casc/testData/credentials-file-with-readFileBase64.yaml"
        val credentialsProvider = testConfig(environmentVariables, cascFileName)

        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as FileCredentials

        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe IDComponent.create("secret-file_via_binary_file")
        credential?.scope shouldBe "GLOBAL"
        credential?.fileName shouldBe "mysecretfile.txt"
        credential?.secretBytes shouldBe "bWlLZXlTdG9yZUluRmlsZQ=="
        credential?.description shouldBe ""

    }

    "resolve should correctly deserialize YAML aws secret to PipelineConfig" {
        val idExpected = IDComponent.create("AWS")
        val environmentVariables = mapOf(
            "AWS_ACCESS_KEY" to "awsAccessKeyTest",
            "AWS_SECRET_ACCESS_KEY" to "awsSecretAccessKeyTest"
        )
        val cascFileName = "casc/testData/credentials-aws.yaml"
        val credentialsProvider = testConfig(environmentVariables, cascFileName)
        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as AwsCredentials


        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe idExpected
        credential?.scope shouldBe "GLOBAL"
        credential?.accessKey shouldBe "awsAccessKeyTest"
        credential?.secretKey shouldBe "awsSecretAccessKeyTest"
        credential?.description shouldBe "AWS Credentials"

    }

    "resolve should correctly deserialize YAML string secret to PipelineConfig" {
        val idExpected = IDComponent.create("secret-text")
        val environmentVariables = mapOf(
            "SECRET_TEXT" to "secretTest",
        )
        val cascFileName = "casc/testData/credentials-string.yaml"
        val credentialsProvider = testConfig(environmentVariables, cascFileName)
        val credential =
            credentialsProvider.getCredentialsById(idExpected).getOrThrow() as StringCredentials

        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe idExpected
        credential?.scope shouldBe "GLOBAL"
        credential?.secret shouldBe "secretTest"
        credential?.description shouldBe "Secret Text"

    }

    "resolve should correctly deserialize YAML string secret with default value to PipelineConfig" {
        val idExpected = IDComponent.create("secret-text")
        val environmentVariables = mapOf("GREETING" to "Hello")

        val cascFileName = "casc/testData/credentials-string-with-default-value.yaml"
        var result: Either<PropertiesError, ICredentialsProvider> =
            Either.Left(PropertiesError("No se ha podido ejecutar el test"))

        val credentialsProvider = testConfig(environmentVariables, cascFileName)
        val credential =
            credentialsProvider.getCredentialsById(idExpected).getOrThrow() as StringCredentials

        result.isRight() shouldBe true
        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe idExpected
        credential?.scope shouldBe "GLOBAL"
        credential?.secret shouldBe "defaultSecretText"
        credential?.description shouldBe "Secret Text"

    }

    "resolve should correctly deserialize YAML string password secret to PipelineConfig" {
        val idExpected = IDComponent.create("username")
        val environmentVariables = mapOf(
            "SOME_USER_PASSWORD" to "userPasswordTest",
        )
        val cascFileName = "casc/testData/credentials-usernamePassword.yaml"
        val credentialsProvider = testConfig(environmentVariables, cascFileName)
        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as UsernamePassword

        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe idExpected
        credential?.scope shouldBe "GLOBAL"
        credential?.username shouldBe "some-user"
        credential?.password shouldBe "userPasswordTest"
        credential?.description shouldBe "Username/Password Credentials for some-user"
    }


})


private suspend fun StringSpecScope.testConfig(
    environmentVariables: Map<String, String>,
    cascFileName: String,
): ICredentialsProvider {
    var result: Either<PropertiesError, ICredentialsProvider> = Either.Left(PropertiesError("No se ha podido ejecutar el test"))
    EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
        val cascManager = CascManager()
        // Ruta al archivo YAML de prueba
        val resourcePath = this::class.java.classLoader.getResource(cascFileName).path
        val testYamlPath = Path.of(resourcePath)

        return@execute runTest() {
            result = either {
                val data = cascManager.getRawConfig(testYamlPath)
                CredentialsProviderFactory.create(data)
            }
        }
    }
    return result.getOrElse { throw Exception("No se ha podido ejecutar el test ${it}") }
}
