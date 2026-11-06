package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.scm.GitRemotePolicy
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.runtime.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInputCodec
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutStepDefinition
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitCheckoutKey
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.registerScmGit
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.scmGitCheckout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * El checkout que construye el DSL del núcleo se baja por el camino canónico.
 *
 * ## La garantía bajo prueba
 *
 * Un checkout escrito en un `.pipeline.kts` con `git(..)` / `checkout(..)` se
 * compila al MISMO step que el que construye la fachada del plugin
 * (`scmGitCheckout(..)`): la misma clave —la que el registro de producción
 * resuelve— y los mismos bytes, los que escribe el codec que el handler del
 * plugin decodifica.
 *
 * ## La capacidad que esta garantía sirve
 *
 * Quien escribe `git("https://…")` espera que el checkout se ejecute. Antes de
 * este arreglo el compilador acuñaba la clave `core.checkout`, que NO está en
 * ningún registro, y escribía el payload del checkout A MANO: el resultado era
 * un step que nadie podía ejecutar y un segundo escritor de un formato que ya
 * tenía dueño. Dos autoridades que pueden divergir, y una de ellas apuntaba a
 * un step muerto.
 *
 * ## Por qué el instrumento es UNITARIO y no una capa más
 *
 * Lo que se rompe aquí es una TRANSLACIÓN: un portador tipado que se proyecta
 * sobre un codec. Las tres cosas que la fila mira —la clave, los bytes, la
 * decodificación de vuelta— son hechos de funciones puras, y una capa por encima
 * (un proceso, un workspace, un repositorio) no podría observar nada que estas
 * no observen; sólo añadiría un sistema de archivos y un reloj a cada
 * aserción. El repositorio no admite una capa E2E.
 *
 * ## Qué NO congela esta fila
 *
 * Que el checkout de verdad clone, que el remoto acepte la credencial y que el
 * árbol quede como lo pidió el autor: eso lo ejecutan el executor y sus pruebas,
 * con un repositorio de verdad.
 */
class CheckoutTranslationTest {

    /**
     * La fila que separa "una frontera" de "dos traducciones": el checkout del
     * núcleo y el de la fachada del plugin son el MISMO step compilado.
     */
    @Test
    fun `el checkout del DSL se baja al step que el plugin registra con el payload de su codec`() {
        val git = GitScm(
            url = URL,
            branch = "main",
            credentialsId = CredentialsId.from("cred-1"),
            changelog = false,
            poll = false,
            relativeTargetDir = "repo",
        )

        val node = compileNode(StepSpec.Checkout(git))

        // --- LA CLAVE: la que el step declara, no una que el compilador inventa ---
        assertEquals(
            GitCheckoutStepDefinition().contract.key,
            node.pluginStepId,
            "el checkout compilado no lleva la clave que el step del plugin declara, " +
                "de modo que el registro de producción no lo puede resolver",
        )
        assertEquals(
            ScmGitCheckoutKey.VALUE,
            node.pluginStepId,
            "la clave del checkout compilado no es la que el manifiesto del plugin publica",
        )
        // --- LA FRONTERA: el registro de producción resuelve esa clave ---
        assertTrue(
            productionRegistry().contains(node.pluginStepId),
            "el registro de producción no contiene la clave del checkout compilado: " +
                "el step no se podría ejecutar",
        )
        // --- EL PAYLOAD: los bytes del codec, no los de otra.writer ---
        assertEquals(
            GitCheckoutInputCodec.encode(git).value,
            node.payload.encoded,
            "el payload del checkout compilado no son los bytes que escribe el codec " +
                "que el plugin decodifica: hay dos escritores del mismo formato",
        )
        // --- Y EL PORTADOR: lo que el codec lee de vuelta es lo que se declaró ---
        assertEquals(
            git,
            GitCheckoutInputCodec.decode(EncodedStepValue(node.payload.encoded)),
            "el payload del checkout compilado no se decodifica al portador que se declaró",
        )
    }

    /**
     * Los valores por defecto que declara el portador sobreviven la traducción:
     * una traducción que rellenara los suyos pondría un checkout donde el autor
     * no pidió ninguno.
     */
    @Test
    fun `los valores por defecto del checkout sobreviven la traduccion`() {
        val scope = StageScope("stage")
        scope.git(url = URL)

        val node = compileNode(scope.steps().single())

        assertEquals(
            GitScm(url = URL),
            GitCheckoutInputCodec.decode(EncodedStepValue(node.payload.encoded)),
            "el checkout compilado no conserva los valores por defecto del portador",
        )
    }

    /**
     * La fachada del plugin y el DSL del núcleo producen el mismo paso: una
     * frontera, no dos traducciones.
     */
    @Test
    fun `la fachada del plugin y el DSL del nucleo producen el mismo paso compilado`() {
        val coreScope = StageScope("stage")
        coreScope.git(url = URL, branch = "main")
        val coreNode = compileNode(coreScope.steps().single())

        val pluginScope = StageScope("stage")
        pluginScope.scmGitCheckout(url = URL, branch = "main")
        val pluginNode = compileNode(pluginScope.steps().single())

        assertEquals(
            pluginNode.pluginStepId,
            coreNode.pluginStepId,
            "el checkout del núcleo y el de la fachada del plugin se bajan a claves distintas",
        )
        assertEquals(
            pluginNode.payload,
            coreNode.payload,
            "el checkout del núcleo y el de la fachada del plugin se bajan a payloads distintos",
        )
    }

    /**
     * Los rechazos siguen siendo de quien los emite: el DSL rechaza lo que el
     * autor escribió mal y el codec rechaza un payload que no describe un
     * checkout. Ninguna traducción se traga un rechazo ni lo fabrica.
     */
    @Test
    fun `los rechazos siguen siendo de quien los emite`() {
        // El DSL: una URL en blanco se rechaza al escribirla, no al compilarla.
        assertThrows<IllegalArgumentException> {
            StageScope("stage").git(url = "  ")
        }
        // El DSL: un remoto con forma de opción se rechaza por su política.
        assertThrows<GitRemotePolicy.GitRemoteRefused> {
            StageScope("stage").git(url = "--upload-pack=id")
        }
        // El codec: un payload sin URL no describe un checkout, y el codec lo dice.
        assertThrows<NoSuchElementException> {
            GitCheckoutInputCodec.decode(EncodedStepValue("{}"))
        }
    }

    /**
     * Compila un step del DSL y devuelve el nodo que produce.
     */
    private fun compileNode(step: StepSpec): OpaqueStepNode {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = listOf(StageSpec(name = "s", steps = listOf(step)))),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return body.steps.single() as OpaqueStepNode
    }

    /**
     * The registry the composition builds in production: the core's own Steps
     * plus the SCM/Git plugin's. The plugin's contributor needs its provenance,
     * so the test pins it instead of letting it come from the machine.
     */
    private fun productionRegistry() =
        CoreStepRegistryFactory.builder()
            .apply {
                registerScmGit(
                    publisher = "pipeline-kotlin",
                    digestSha256 = "sha256:" + "0".repeat(64),
                )
            }
            .build()

    private companion object {
        const val URL = "https://example.org/repo.git"
    }
}
