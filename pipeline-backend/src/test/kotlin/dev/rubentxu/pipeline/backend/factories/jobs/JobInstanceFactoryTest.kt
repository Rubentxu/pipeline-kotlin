package dev.rubentxu.pipeline.backend.factories.jobs

import arrow.core.Either
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.toPropertySet
import dev.rubentxu.pipeline.model.PropertiesError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class JobInstanceFactoryTest : StringSpec({

    "Should create a JobInstance from a JobConfig" {
        val jobConfig: PropertySet =
            mapOf(
                "pipeline" to mapOf(
                    "name" to "pipeline-job",
                    "trigger" to mapOf(
                        "cron" to "H/5 * * * *"
                    ),
                    "environmentVars" to mapOf(
                        "JOB_NAME" to "Ejemplo-pipeline",
                        "MENSAJE" to "Hola Mundo"
                    ),
                    "publisher" to mapOf(
                        "mailer" to mapOf(
                            "recipients" to "admin@localhost",
                            "notifyEveryUnstableBuild" to true,
                            "sendToIndividuals" to true
                        ),
                        "archiveArtifacts" to mapOf(
                            "artifacts" to "target/*.jar",
                            "allowEmptyArchive" to true,
                            "onlyIfSuccessful" to true,
                            "fingerprint" to true
                        )
                    )
                )
            ) as PropertySet

        val jobInstance = JobInstanceFactory.create(jobConfig)

        jobInstance.isRight() shouldBe false
        jobInstance shouldBe Either.Left(PropertiesError("pipeline name is required"))
        jobInstance.getOrNull()?.name shouldBe "pipeline-job"

    }

})
