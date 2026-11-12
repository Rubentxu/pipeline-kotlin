package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.directive.AgentLabel
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Lo que el pipeline declara, aplicado a los stages que no declaran lo suyo.
 *
 * ## La garantía bajo prueba
 *
 * Un `pipeline { }` puede declarar sus variables con `environment { }` y su
 * agente con `agent(..)`. Lo que declara es el DEFAULT de cada stage: un stage
 * que no declara una variable la hereda, y un stage que declara una variable
 * PROPIA la conserva — y lo mismo con el agente. La precedencia es la que el
 * gate ya usa: una declaración gana sobre un valor que llega de fuera, y se
 * decide NOMBRE POR NOMBRE.
 *
 * ## Por qué se mide el PLAN COMPILADO y no el `PipelineSpec`
 *
 * Porque el plan compilado es lo que el motor ejecuta: es donde las variables
 * llegan al entorno del proceso del paso y donde la directiva del agente llega
 * al resolutor. Un `PipelineSpec` que llevara las variables y el agente
 * probaría que el DSL los guardó, no que el motor los recibe. El plan es el
 * ÚNICO sitio donde las dos cosas se juntan, así que es el único sitio donde
 * la garantía se puede medir.
 *
 * ## Por qué el agente se MIDE y no se cuenta
 *
 * Una directiva `core.agent` que llega al plan con la clave correcta y una
 * carga útil vacía no es un agente: es una puerta que el resolutor abriría sin
 * saber contra qué. La fila decodifica la carga con el códec que la escribió y
 * compara la EXIGENCIA, que es lo que el resolutor decide.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipelineLevelDefaultsTest {

    /**
     * El plan compilado lleva las variables del pipeline y el agente por
     * defecto en el stage que no declara ninguno.
     */
    @Test
    fun `el plan compilado lleva las variables del pipeline y el agente por defecto`() {
        val compiled = compiledOf(
            pipeline {
                environment { env("TARGET", "staging") }
                agent(label = "linux")
                stages {
                    stage("build") {
                        echo("hello")
                    }
                }
            },
        )

        val stage = compiled.stages.single()
        assertEquals(
            mapOf("TARGET" to "staging"),
            stage.environment.values,
            "el stage no declara ninguna variable, así que corre con las del pipeline",
        )
        assertEquals(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("linux"))),
            requirementOf(stage.directives),
            "el stage no declara ningún agente, así que el del pipeline es el suyo",
        )
    }

    /**
     * La declaración de un stage gana sobre la del pipeline, nombre por nombre.
     */
    @Test
    fun `la declaracion de un stage gana sobre la del pipeline`() {
        val compiled = compiledOf(
            pipeline {
                environment {
                    env("TARGET", "from-the-pipeline")
                    env("REGISTRY", "from-the-pipeline")
                }
                stages {
                    stage("build") {
                        environment { env("TARGET", "from-the-stage") }
                        echo("hello")
                    }
                }
            },
        )

        val stage = compiled.stages.single()
        assertEquals(
            mapOf(
                "TARGET" to "from-the-stage",
                "REGISTRY" to "from-the-pipeline",
            ),
            stage.environment.values,
            "el stage gana en el nombre que declara y hereda el que no declara: " +
                "la precedencia se decide nombre por nombre",
        )
    }

    /**
     * El agente que declara un stage gana sobre el del pipeline.
     */
    @Test
    fun `el agente que declara un stage gana sobre el del pipeline`() {
        val compiled = compiledOf(
            pipeline {
                agent(label = "from-the-pipeline")
                stages {
                    stage("build") {
                        agent(label = "from-the-stage")
                        echo("hello")
                    }
                }
            },
        )

        val stage = compiled.stages.single()
        assertEquals(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("from-the-stage"))),
            requirementOf(stage.directives),
            "el agente del stage es el suyo: dos exigencias serían dosleases, " +
                "y el autor pidió uno",
        )
    }

    /**
     * El plan compilado lleva el agente del pipeline a CADA stage que no
     * declara el suyo, y a ninguno de los que lo declaran.
     */
    @Test
    fun `el agente del pipeline llega a cada stage que no declara el suyo`() {
        val compiled = compiledOf(
            pipeline {
                agentAny()
                stages {
                    stage("first") {
                        echo("one")
                    }
                    stage("second") {
                        agent(label = "own")
                        echo("two")
                    }
                }
            },
        )

        val first = compiled.stages.single { it.name == "first" }
        val second = compiled.stages.single { it.name == "second" }
        assertEquals(
            ExecutionTargetRequirement.LocalAny,
            requirementOf(first.directives),
            "el primer stage no declara agente, así que hereda el del pipeline",
        )
        assertEquals(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("own"))),
            requirementOf(second.directives),
            "el segundo stage declara su propio agente, así que el del pipeline no le llega",
        )
    }

    /**
     * La exigencia que viaja en la directiva del agente, DECODIFICADA.
     *
     * La carga la escribió el códec del dominio, y la lee el mismo códec: una
     * fila que comparara la carga con una constante escrita a mano mediría una
     * cadena, no la exigencia.
     */
    private fun requirementOf(directives: List<StageDirective>): ExecutionTargetRequirement {
        val agents = directives.filter { it.key == AGENT_DIRECTIVE_KEY.value }
        assertEquals(
            1,
            agents.size,
            "un stage declara un agente: el del pipeline o el suyo, nunca los dos",
        )
        val decoded = ExecutionTargetRequirementCodec.decode(agents.single().encodedArguments)
        return when (decoded) {
            is DirectiveDecodeResult.Decoded -> decoded.input
            is DirectiveDecodeResult.Malformed -> error(
                "the agent directive carries a payload the codec cannot read: ${decoded.reason}",
            )
        }
    }

    /**
     * El plan compilado de un `PipelineSpec`.
     */
    private fun compiledOf(spec: PipelineSpec) = DslCompiledPipelineCompiler.compile(
        spec = spec,
        sourcePath = "pipeline.kts",
        sourceContent = "pipeline { }",
        pluginLockDigest = Digest("lock"),
    )
}
