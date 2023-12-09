package dev.rubentxu.pipeline.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class JobConfigTest : StringSpec({
    "Should get Job Config from the job config file" {
        val cascManager = CascManager()


        val resourcePath = PipelineConfigTest::class.java.classLoader.getResource("casc/job.yaml").path
        val testYamlPath = Path.of(resourcePath)


        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

        config.jobs?.size shouldBe 1



    }


})
