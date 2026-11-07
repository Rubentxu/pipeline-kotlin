package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.runtime.ArchiveArtifactsInput
import dev.rubentxu.pipeline.v2.runtime.CoreArchiveArtifactsWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreDeleteDirWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreFileExistsInput
import dev.rubentxu.pipeline.v2.runtime.CoreFileExistsWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreReadFileInput
import dev.rubentxu.pipeline.v2.runtime.CoreReadFileWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreWriteFileInput
import dev.rubentxu.pipeline.v2.runtime.CoreWriteFileWireCodec
import dev.rubentxu.pipeline.v2.runtime.DeleteDirInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * El compilador DELEGA el wire de las familias de fichero, y el byte no se mueve.
 *
 * ## LA GARANTÍA BAJO PRUEBA
 *
 * El payload compilado de una familia es EXACTAMENTE el que su codec escribe, y ese
 * payload es byte a byte el que el compilador escribía a mano antes de esta tarea.
 * Son tres afirmaciones y las tres tienen que caer juntas:
 *
 * 1. EL BYTE NO SE MUEVE. El payload compilado se hashea en el fingerprint durable,
 *    así que un byte distinto no es cosmético: un resume de un run afectado ENCUENTRA
 *    su fila, ve un fingerprint DIVERGIDO y aborta fail-closed en vez de reejecutar el
 *    paso. Por eso el literal de cada familia está escrito aquí, a mano, como el byte
 *    histórico: es la caracterización del wire, no una expectativa sobre el código.
 * 2. EL BYTE ES EL DEL CODEC. El payload compilado es EXACTAMENTE el que produce el
 *    codec, y el codec vuelve a leerlo y devuelve la misma entrada. Lo que esta fila
 *    AFIRMA es la coincidencia de los bytes; lo que AFIRMA QUE EL COMPILADOR DELEGA
 *    —y no que coincide por casualidad— es el guard de la etapa 1
 *    (`scripts/check-compiler-wire-authority.py`), que falla si el compilador vuelve
 *    a NOMBRAR a mano el `kind` de una familia que un codec dueno. Son instrumentos
 *    DISTINTOS de la MISMA garantía, y por eso los dos existen: una comparación de
 *    bytes no puede distinguir a quien escribe de quien coincide.
 * 3. PRODUCTOR Y CONSUMIDOR SE ENTIENDEN. El codec DECODIFICA lo que el compilador
 *    escribió y devuelve la misma entrada. Un codec que no puede leer lo que su
 *    productor escribe no es una autoridad: es una segunda formato con la misma
 *    etiqueta.
 *
 * ## POR QUÉ UNA FILA POR FAMILIA
 *
 * Las tres afirmaciones de arriba tienen la MISMA causa dentro de cada familia (el
 * codec de ESA familia), y familias distintas tienen causas distintas: un cambio en
 * `CoreArchiveArtifactsWireCodec` no puede mover el byte de `readFile`. Una fila por
 * familia da esa atribución: cuando algo cae, el veredicto nombra QUÉ se rompió, no
 * sólo cuánto.
 *
 * ## LO QUE ESTE FICHERO NO AFIRMA
 *
 * No afirma nada sobre las familias que NO tienen codec dueno (`echo`, `error`,
 * `sleep`, `cleanWs`, `pwd`, `load`, `ansiColor`, `node`): para ésas el compilador
 * sigue siendo el único autor, y su envelope lo escribe la tabla de `encodePayload`.
 * Una regla que nombrara un formato que nadie dueno sería ruido.
 */
class CompilerWireAuthorityTest {

    /**
     * `writeFile`: el byte es el de siempre, el codec lo escribe, y el codec lo relee.
     */
    @Test
    fun `writeFile compila al payload del codec y el byte no se mueve`() {
        val node = compileSingle(StepSpec.WriteFile(FILE, TEXT, ENCODING))
        val input = CoreWriteFileInput(file = FILE, text = TEXT, encoding = ENCODING)

        assertEquals(
            PluginStepId("core.file.writeFile"),
            node.pluginStepId,
            "la familia writeFile baja al paso que la dueno, y no a otro",
        )
        assertEquals(
            """{"kind":"writeFile","file":"artefacto.txt","text":"contenido declarado","encoding":"UTF-8"}""",
            node.payload.encoded,
            "el payload compilado es byte a byte el que el compilador escribía a mano: un " +
                "byte distinto aborta el resume de un run ya publicado",
        )
        assertEquals(
            CoreWriteFileWireCodec.encode(input).value,
            node.payload.encoded,
            "el payload compilado es el que produce el codec: que el compilador DELEGE y " +
                "no que coincida por casualidad lo afirma el guard de la etapa 1, no esta fila",
        )
        assertEquals(
            input,
            CoreWriteFileWireCodec.decode(EncodedStepValue(node.payload.encoded)),
            "el codec lee lo que el compilador escribió: un codec que no puede leer a su " +
                "productor no es una autoridad",
        )
    }

    /**
     * `readFile`: el byte es el de siempre, el codec lo escribe, y el codec lo relee.
     */
    @Test
    fun `readFile compila al payload del codec y el byte no se mueve`() {
        val node = compileSingle(StepSpec.ReadFile(file = FILE, encoding = ENCODING))
        val input = CoreReadFileInput(file = FILE, encoding = ENCODING)

        assertEquals(
            PluginStepId("core.readFile"),
            node.pluginStepId,
            "la familia readFile baja al paso que la dueno, y no a otro",
        )
        assertEquals(
            """{"kind":"readFile","file":"artefacto.txt","encoding":"UTF-8"}""",
            node.payload.encoded,
            "el payload compilado es byte a byte el que el compilador escribía a mano: un " +
                "byte distinto aborta el resume de un run ya publicado",
        )
        assertEquals(
            CoreReadFileWireCodec.encode(input).value,
            node.payload.encoded,
            "el payload compilado es el que produce el codec: que el compilador DELEGE y " +
                "no que coincida por casualidad lo afirma el guard de la etapa 1, no esta fila",
        )
        assertEquals(
            input,
            CoreReadFileWireCodec.decode(EncodedStepValue(node.payload.encoded)),
            "el codec lee lo que el compilador escribió: un codec que no puede leer a su " +
                "productor no es una autoridad",
        )
    }

    /**
     * `fileExists`: el byte es el de siempre, el codec lo escribe, y el codec lo relee.
     */
    @Test
    fun `fileExists compila al payload del codec y el byte no se mueve`() {
        val node = compileSingle(StepSpec.FileExists(file = FILE))
        val input = CoreFileExistsInput(file = FILE)

        assertEquals(
            PluginStepId("core.fileExists"),
            node.pluginStepId,
            "la familia fileExists baja al paso que la dueno, y no a otro",
        )
        assertEquals(
            """{"kind":"fileExists","file":"artefacto.txt"}""",
            node.payload.encoded,
            "el payload compilado es byte a byte el que el compilador escribía a mano: un " +
                "byte distinto aborta el resume de un run ya publicado",
        )
        assertEquals(
            CoreFileExistsWireCodec.encode(input).value,
            node.payload.encoded,
            "el payload compilado es el que produce el codec: que el compilador DELEGE y " +
                "no que coincida por casualidad lo afirma el guard de la etapa 1, no esta fila",
        )
        assertEquals(
            input,
            CoreFileExistsWireCodec.decode(EncodedStepValue(node.payload.encoded)),
            "el codec lee lo que el compilador escribió: un codec que no puede leer a su " +
                "productor no es una autoridad",
        )
    }

    /**
     * `deleteDir`: el byte es el de siempre, el codec lo escribe, y el codec lo relee.
     *
     * Esta familia es la que el compilador degradaba a `{"kind":"deleteDir",
     * "declarativeValue":…}`: el decode leía el `path` AUSENTE como el raíz del stage, y
     * la familia borraba el directorio equivocado sin decir nada. El byte de hoy es el
     * canónico, y el que lo escribe es el codec.
     */
    @Test
    fun `deleteDir compila al payload del codec y el byte no se mueve`() {
        val node = compileSingle(StepSpec.DeleteDir(path = "sub"))
        val input = DeleteDirInput(path = "sub")

        assertEquals(
            PluginStepId("core.deleteDir"),
            node.pluginStepId,
            "la familia deleteDir baja al paso que la dueno, y no a otro",
        )
        assertEquals(
            """{"kind":"deleteDir","path":"sub"}""",
            node.payload.encoded,
            "el payload compilado es byte a byte el que el compilador escribía a mano: un " +
                "byte distinto aborta el resume de un run ya publicado",
        )
        assertEquals(
            CoreDeleteDirWireCodec.encode(input).value,
            node.payload.encoded,
            "el payload compilado es el que produce el codec: que el compilador DELEGE y " +
                "no que coincida por casualidad lo afirma el guard de la etapa 1, no esta fila",
        )
        assertEquals(
            input,
            CoreDeleteDirWireCodec.decode(EncodedStepValue(node.payload.encoded)),
            "el codec lee lo que el compilador escribió: un codec que no puede leer a su " +
                "productor no es una autoridad",
        )
    }

    /**
     * `archiveArtifacts`: el byte es el de siempre, el codec lo escribe, y el codec lo relee.
     *
     * Es la familia con MÁS defaults ocultos en el decode (`allowEmptyArchive`,
     * `excludes` y `fingerprint` se leen ausentes-como-default), así que es la que más
     * Suffre de un segundo autor: un productor que olvida uno de esos tres campos no
     * falla, archiva otra cosa. De ahí que se migrara la primera.
     */
    @Test
    fun `archiveArtifacts compila al payload del codec y el byte no se mueve`() {
        val node = compileSingle(
            StepSpec.ArchiveArtifacts(
                artifacts = PATTERN,
                allowEmptyArchive = true,
                excludes = EXCLUDES,
                fingerprint = true,
                artifactName = NAME,
            ),
        )
        val input = ArchiveArtifactsInput(
            artifacts = PATTERN,
            allowEmptyArchive = true,
            excludes = EXCLUDES,
            fingerprint = true,
            name = NAME,
        )

        assertEquals(
            PluginStepId("core.archiveArtifacts"),
            node.pluginStepId,
            "la familia archiveArtifacts baja al paso que la dueno, y no a otro",
        )
        assertEquals(
            """{"kind":"archiveArtifacts","artifacts":"**/*.txt","allowEmptyArchive":true,""" +
                """"excludes":"**/*.tmp","fingerprint":true,"name":"informes"}""",
            node.payload.encoded,
            "el payload compilado es byte a byte el que el compilador escribía a mano, " +
                "incluido el `name` opcional al final: un byte distinto aborta el resume " +
                "de un run ya publicado",
        )
        assertEquals(
            CoreArchiveArtifactsWireCodec.encode(input).value,
            node.payload.encoded,
            "el payload compilado es el que produce el codec: que el compilador DELEGE y " +
                "no que coincida por casualidad lo afirma el guard de la etapa 1, no esta fila",
        )
        assertEquals(
            input,
            CoreArchiveArtifactsWireCodec.decode(EncodedStepValue(node.payload.encoded)),
            "el codec lee lo que el compilador escribió: un codec que no puede leer a su " +
                "productor no es una autoridad",
        )
    }

    /**
     * `archiveArtifacts` SIN nombre: el `name` AUSENTE sigue siendo lo que el codec
     * escribe cuando el autor no declara ninguno.
     *
     * Es una partición aparte porque el `name` es el ÚNICO campo opcional de la
     * familia: un autor que no lo declara y un autor que declara uno vacío NO son la
     * misma cosa, y el byte distingue las dos. Si el codec lo emitiera siempre, el
     * payload de un run ya publicado se movería.
     */
    @Test
    fun `archiveArtifacts sin nombre no emite el campo name`() {
        val node = compileSingle(StepSpec.ArchiveArtifacts(artifacts = PATTERN))

        assertEquals(
            """{"kind":"archiveArtifacts","artifacts":"**/*.txt","allowEmptyArchive":false,""" +
                """"excludes":"","fingerprint":false}""",
            node.payload.encoded,
            "un autor que no declara nombre no viaja con un campo `name`: el codec lo emite " +
                "sólo cuando no es nulo, y ese byte es el que un run ya publicado tiene",
        )
    }

    /**
     * La invariante de cada familia la RECHAZA el compilador, y la rechaza con el
     * mensaje de la propia familia.
     *
     * ## POR QUÉ ESTA FILA EXISTE
     *
     * Delegar el encoding en el codec tiene un precio, y el precio es que el
     * compilador deja de poder aceptar una forma que la familia declara inválida. La
     * proyección proyecta sobre la ENTRADA TIPADA, y la entrada tipada es la que
     * rechaza un `file` en blanco. Antes, el compilador escribía el envelope a mano y
     * NO validaba nada: producía un payload que el runtime rechazaba al ejecutar el
     * paso.
     *
     * El rechazo no cambia: es el mismo, con el mismo mensaje, por la misma autoridad.
     * Lo que cambia es CUÁNDO llega — antes de que el run empiece, en vez de en
     * mitad de la ejecución — y una validación que llega tarde no es una guarda. Un
     * run que iba a fallar en su primer paso ahora no arranca, y el motivo dice qué
     * se declaró mal.
     *
     * `deleteDir` NO aparece aquí: su `path` puede ser el raíz del stage, que es un
     * valor legítimo, así que no hay invariante que heredar. Esa family's `path` en
     * blanco NI siquiera es un caso: el codec lo acepta tal cual.
     */
    @Test
    fun `una forma invalida la rechaza el compilador con el mensaje de la familia`() {
        assertEquals(
            "core.file.writeFile requires a non-blank file",
            rejectionOf(StepSpec.WriteFile(file = " ", text = TEXT, encoding = ENCODING)),
            "la familia writeFile rechaza un file en blanco, y lo rechaza al COMPILAR",
        )
        assertEquals(
            "core.readFile requires a non-blank file",
            rejectionOf(StepSpec.ReadFile(file = " ", encoding = ENCODING)),
            "la familia readFile rechaza un file en blanco, y lo rechaza al COMPILAR",
        )
        assertEquals(
            "core.fileExists requires a non-blank file",
            rejectionOf(StepSpec.FileExists(file = " ")),
            "la familia fileExists rechaza un file en blanco, y lo rechaza al COMPILAR",
        )
        assertEquals(
            "core.archiveArtifacts requires a non-blank artifacts pattern",
            rejectionOf(StepSpec.ArchiveArtifacts(artifacts = " ")),
            "la familia archiveArtifacts rechaza un patrón en blanco, y lo rechaza al COMPILAR",
        )
        assertEquals(
            "core.archiveArtifacts 'name' must be null or non-blank (got blank)",
            rejectionOf(StepSpec.ArchiveArtifacts(artifacts = PATTERN, artifactName = " ")),
            "la familia archiveArtifacts rechaza un nombre en blanco, y lo rechaza al COMPILAR",
        )
    }

    /**
     * El mensaje con el que la familia rechaza la forma inválida, al compilar.
     */
    private fun rejectionOf(step: StepSpec): String {
        val failure = assertThrows(
            IllegalArgumentException::class.java,
            { compileSingle(step) },
            "una forma que la familia declara inválida tiene que ser rechazada al compilar, " +
                "no entregada al runtime para que la rechace a mitad del run",
        )
        return failure.message.orEmpty()
    }

    /**
     * El nodo que produce el compilador para un stage con este paso.
     *
     * El `Digest` va con el nombre COMPLETO porque el dominio publica DOS tipos con ese
     * nombre —`dev.rubentxu.pipeline.v2.domain.Digest` y
     * `dev.rubentxu.pipeline.v2.domain.step.Digest`— y un fichero que importa el primero
     * deja de resolver el paquete `domain.step`. El nombre completo mantiene este
     * fichero independiente de esa colisión y del orden de los imports, que un
     * formateador puede cambiar.
     */
    private fun compileSingle(step: StepSpec): StepNode {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = listOf(StageSpec(name = "s", steps = listOf(step)))),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return body.steps.single()
    }

    private companion object {
        const val FILE = "artefacto.txt"
        const val TEXT = "contenido declarado"
        const val ENCODING = "UTF-8"
        const val PATTERN = "**/*.txt"
        const val EXCLUDES = "**/*.tmp"
        const val NAME = "informes"
    }
}
