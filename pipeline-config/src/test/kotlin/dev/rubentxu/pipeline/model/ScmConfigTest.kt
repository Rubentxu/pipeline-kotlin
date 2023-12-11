package dev.rubentxu.pipeline.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class ScmConfigTest : StringSpec({

    "Should get Job Config from the job config file" {
        val cascManager = CascManager()


        val resourcePath = PipelineConfigTest::class.java.classLoader.getResource("casc/scm.yaml").path
        val testYamlPath = Path.of(resourcePath)


        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

        config.scm.definitions.size shouldBe 4
        config.scm.definitions[0].sourceRepository shouldBe RemoteRepository(url="https://github.com/ejemplo/repo-remoto.git", credentialsId="credenciales-remotas")
        config.scm.definitions[0].branches shouldBe listOf("main")
        (config.scm.definitions[0] as GitScmConfig).extensions shouldBe listOf(
            SimpleSCMExtension(name="shallowClone", value=true),
            SimpleSCMExtension(name="timeout", value=10),
            SimpleSCMExtension(name="lfs", value=true),
            SimpleSCMExtension(name="submodules", value=true),
            SimpleSCMExtension(name="cloneOptions", value="--depth 1"),
            SimpleSCMExtension(name="sparseCheckoutPaths", value= listOf("carpeta1", "carpeta2", "carpeta3")),
            SimpleSCMExtension(name="relativeTargetDirectory", value="carpeta")
        )
        (config.scm.definitions[0] as GitScmConfig).globalConfigName shouldBe "root"
        (config.scm.definitions[0] as GitScmConfig).globalConfigEmail shouldBe "root@localhost"


        config.scm.definitions[1].sourceRepository shouldBe LocalRepository(path="/ruta/local/repo-local.git", isBareRepo=true)
        config.scm.definitions[2].sourceRepository shouldBe RemoteRepository(url="http://svn.ejemplo.com/repo-remoto", credentialsId="credenciales-svn")
        config.scm.definitions[3].sourceRepository shouldBe RemoteRepository(url="http://hg.ejemplo.com/repo-remoto", credentialsId="")


    }
})
