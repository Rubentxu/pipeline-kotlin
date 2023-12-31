package dev.rubentxu.pipeline.backend.config

import dev.rubentxu.pipeline.backend.cdi.CascManager
import dev.rubentxu.pipeline.backend.factories.credentials.CredentialsFactory
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.credentials.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.scopes.StringSpecScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.ExtendWith
import pipeline.kotlin.extensions.LookupException
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Path

@ExtendWith(SystemStubsExtension::class)
class CredentialsManagerTest : StringSpec({
    "resolveConfig should correctly deserialize YAML secrets with basic SSH Private Key to PipelineConfig" {
        val environmentVariables = mapOf(
            "SSH_KEY_PASSWORD" to "miSSHKeyPassword",
            "SSH_PRIVATE_KEY" to "miSSHPrivateKey"
        )
        val idExpected = IDComponent.create("ssh_with_passphrase_provided")
        val cascFileName = "casc/testData/credentials-basicSSH_Private_Key.yaml"
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credentialsProvider = result.getOrThrow().getService(ICredentialsProvider::class).getOrThrow()

        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as BasicSSHUserPrivateKey

        result.isSuccess shouldBe true
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)

        val credentialsProvider = result.getOrThrow().getService(ICredentialsProvider::class).getOrThrow()

        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as BasicSSHUserPrivateKey

        result.isSuccess shouldBe true
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credentialsProvider = result.getOrThrow().getService(ICredentialsProvider::class).getOrThrow()

        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as CertificateCredentials

        result.isSuccess shouldBe true
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))


        val exception = shouldThrow<LookupException> {
            testConfig(environmentVariables, cascFileName, result).getOrThrow()
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credentialsProvider = result.getOrThrow().getService(ICredentialsProvider::class).getOrThrow()


        val credential = credentialsProvider.getCredentialsById(idExpected).getOrThrow() as FileCredentials

        result.isSuccess shouldBe true
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        val exception = shouldThrow<LookupException> {
            testConfig(environmentVariables, cascFileName, result).getOrThrow()
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        val credentialsProvider = result.getOrThrow().getService(ICredentialsProvider::class).getOrThrow()

        result = testConfig(environmentVariables, cascFileName, result)
        val credential = credentialsProvider.getCredentialsById(IDComponent.create("")).getOrThrow() as FileCredentials

        result.isSuccess shouldBe true
        credentialsProvider.listCredentials().size shouldBe 1
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)

        val credentialsProvider = result.getOrThrow().getService(ICredentialsProvider::class).getOrThrow()
        val credential = credentialsProvider.getCredentialsById(IDComponent.create("")).getOrThrow() as AwsCredentials

        result.isSuccess shouldBe true
        credentialsProvider.listCredentials().size shouldBe 1
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)

        val credentialsProvider = result.getOrThrow().getService(ICredentialsProvider::class).getOrThrow()
        val credential =
            credentialsProvider.getCredentialsById(IDComponent.create("")).getOrThrow() as StringCredentials

        result.isSuccess shouldBe true
        credentialsProvider.listCredentials().size shouldBe 1
        credential?.id shouldBe "secret-text"
        credential?.scope shouldBe "GLOBAL"
        credential?.secret shouldBe "secretTest"
        credential?.description shouldBe "Secret Text"

    }

    "resolve should correctly deserialize YAML string secret with default value to PipelineConfig" {
        val environmentVariables = mapOf("GREETING" to "Hello")

        val cascFileName = "casc/testData/credentials-string-with-default-value.yaml"
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credentialsProvider = result.getOrThrow().getService(ICredentialsProvider::class).getOrThrow()
        val credential =
            credentialsProvider.getCredentialsById(IDComponent.create("")).getOrThrow() as StringCredentials

        result.isSuccess shouldBe true
        credentialsProvider.listCredentials().size shouldBe 1
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
        var result: Result<List<Credentials>> = Result.failure(Exception("No se ha podido ejecutar el test"))

        result = testConfig(environmentVariables, cascFileName, result)
        val credentialsProvider = result.getOrThrow()
        val credential = credentialsProvider.getCredentialsById(IDComponent.create("")).getOrThrow() as UsernamePassword

        result.isSuccess shouldBe true
        credentialsProvider.listCredentials().size shouldBe 1
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
    result: Result<List<Credentials>>,
): Result<List<Credentials>> {
    var result1 = result
    EnvironmentVariables(environmentVariables).execute {// Crear una instancia de CascManager
        val cascManager = CascManager()
        // Ruta al archivo YAML de prueba
        val resourcePath = this::class.java.classLoader.getResource(cascFileName).path
        val testYamlPath = Path.of(resourcePath)

        val data = CascManager().getRawConfig(testYamlPath).getOrThrow()

        // Ejecutar resolveConfig
        runTest() {
            result1 = Result.success(CredentialsFactory.create(data).list)

        }
    }
    return result1
}
