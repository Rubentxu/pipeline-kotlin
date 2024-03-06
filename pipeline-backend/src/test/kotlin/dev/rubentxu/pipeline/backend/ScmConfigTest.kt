package dev.rubentxu.pipeline.backend

import io.kotest.core.spec.style.StringSpec
import java.nio.file.Path

class ScmConfigTest : StringSpec({

    "Should get Job Config from the job config file" {
//        val cascManager = CascManager()


        val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/scm.yaml").path
        val testYamlPath = Path.of(resourcePath)


//        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

//        config.scm.definitions.size shouldBe 4
//        config.scm.definitions[0].id shouldBe IDComponent.create(id="git-id")
//        config.scm.definitions[0].sourceRepository shouldBe RemoteRepository(url= URL("https://github.com/ejemplo/repo-remoto.git"), credentialsId="credenciales-remotas")
//        config.scm.definitions[0].branches shouldBe listOf("main")
//        (config.scm.definitions[0] as GitSourceCodeRepository).extensions shouldBe listOf(
//            SimpleSCMExtension(name="shallowClone", value=true),
//            SimpleSCMExtension(name="timeout", value=10),
//            SimpleSCMExtension(name="lfs", value=true),
//            SimpleSCMExtension(name="submodules", value=true),
//            SimpleSCMExtension(name="cloneOptions", value="--depth 1"),
//            SimpleSCMExtension(name="sparseCheckoutPaths", value= listOf("carpeta1", "carpeta2", "carpeta3")),
//            SimpleSCMExtension(name="relativeTargetDirectory", value="carpeta")
//        )
//        (config.scm.definitions[0] as GitSourceCodeRepository).globalConfigName shouldBe "root"
//        (config.scm.definitions[0] as GitSourceCodeRepository).globalConfigEmail shouldBe "root@localhost"
//
//
//        config.scm.definitions[1].id shouldBe IDComponent.create(id="local-git-id")
//        config.scm.definitions[1].sourceRepository shouldBe LocalSourceCodeRepository(path= Path.of("/ruta/local/repo-local.git"), isBareRepo=true)
//
//        config.scm.definitions[2].sourceRepository shouldBe RemoteRepository(url=URL("http://svn.ejemplo.com/repo-remoto"), credentialsId="credenciales-svn")
//        config.scm.definitions[3].sourceRepository shouldBe RemoteRepository(url=URL("http://hg.ejemplo.com/repo-remoto"), credentialsId="")


    }
})
