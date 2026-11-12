package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * El agente que declara el PIPELINE, y lo que se rechaza cuando declara dos.
 *
 * ## La garantía bajo prueba
 *
 * El pipeline declara UN agente, y el agente es el que corre por defecto en
 * los stages que no declaran el suyo. Un pipeline que declara un segundo agente
 * es rechazado donde lo declara, y no por el plan: la segunda declaración
 * pondría dos exigencias de la misma directiva en cada stage que no declara
 * agente, y un plan que lleva la misma clave dos veces lo rechaza el
 * planificador.
 *
 * ## Por qué el rechazo vive AQUÍ y no en el plan
 *
 * Porque el planificador rechaza un PLAN, y su veredicto nombra el stage, no
 * la línea que lo declara. El rechazo de la declaración nombra la
 * contradicción y la línea que la escribe, que es lo que el autor tiene
 * delante. Los dos rechazos se cierran —el plan nunca llega a llevar dos
 * agentes—, pero sólo uno de los dos dice qué hay que corregir.
 *
 * ## Lo que esta clase NO mide
 *
 * Que el agente del pipeline llegue a los stages es cosa del PLAN COMPILADO, y
 * lo mide una fila del módulo que lo compila. Aquí lo que se mide es la regla
 * del DSL: un pipeline declara un agente o ninguno.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipelineLevelAgentTest {

    /**
     * Un pipeline que declara DOS agentes es rechazado donde lo declara.
     */
    @Test
    fun `un pipeline que declara dos agentes es rechazado`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                agent(label = "first")
                agent(label = "second")
            }
        }

        assertTrue(
            refusal.message?.contains("the pipeline declares ONE agent") == true,
            "el rechazo nombra la contradicción y lo que hay que hacer: " +
                "el mensaje fue '${refusal.message}'",
        )
    }

    /**
     * Un pipeline que declara UN agente se construye, y el stage que no declara
     * ninguno lleva esa exigencia y no otra.
     *
     * La fila es la contrapartida de la del rechazo: la regla es sobre el
     * NÚMERO de declaraciones del pipeline, no sobre su contenido.
     */
    @Test
    fun `un pipeline que declara un solo agente se construye`() {
        val spec = pipeline {
            agentAny()
            stages {
                stage("build") { echo("hello") }
            }
        }

        val agents = spec.stages.single().directives
            .filter { it.key == AGENT_DIRECTIVE_KEY.value }
        assertEquals(
            1,
            agents.size,
            "el pipeline declara un agente y el stage que no declara ninguno lo hereda: " +
                "el stage lleva UNA exigencia de agente, la del pipeline",
        )
    }
}
