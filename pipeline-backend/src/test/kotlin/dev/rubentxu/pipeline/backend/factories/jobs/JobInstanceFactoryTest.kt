package dev.rubentxu.pipeline.backend.factories.jobs

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class JobInstanceFactoryTest : StringSpec({

    "Should create a JobInstance from a JobConfig" {
        val jobConfig: Map<String, Any> =
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
            )

        val jobInstance = JobInstanceFactory.create(jobConfig)

        jobInstance.name shouldBe "pipeline-job"

    }

})
