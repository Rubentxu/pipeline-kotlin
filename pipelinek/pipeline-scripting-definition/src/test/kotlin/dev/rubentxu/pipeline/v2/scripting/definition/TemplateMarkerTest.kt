package dev.rubentxu.pipeline.v2.scripting.definition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlin.script.experimental.annotations.KotlinScript

/**
 * An IDE finds the definition of a `.pipeline.kts` by its template marker, and nothing else.
 *
 * The Kotlin toolchain discovers a script definition on a classpath by listing
 * `META-INF/kotlin/script/templates/` and reading each `<class name>.classname` FILE NAME as the
 * name of a `@KotlinScript` class. Nothing generates the marker: it is a resource written by hand,
 * so a renamed class or a moved package leaves a marker that names a class that does not exist,
 * the runner keeps compiling, and only the IDE stops recognising the scripts.
 */
class TemplateMarkerTest {

    private val markerDirectory = "META-INF/kotlin/script/templates/"

    @Test
    fun `the marker names the definition class, and the class is the definition of a pipeline script`() {
        val marker = markerDirectory + PipelineScript::class.java.name + ".classname"
        assertNotNull(
            javaClass.classLoader.getResource(marker),
            "the artifact carries no template marker for the definition, so an IDE does not find it: $marker",
        )

        val named = Class.forName(marker.removePrefix(markerDirectory).removeSuffix(".classname"))
        val annotation = named.getAnnotation(KotlinScript::class.java)
        assertNotNull(annotation, "the class the marker names is not a @KotlinScript definition: ${named.name}")
        assertEquals("pipeline.kts", annotation.fileExtension)
        assertEquals(PipelineScriptCompilationConfiguration::class, annotation.compilationConfiguration)
    }
}
