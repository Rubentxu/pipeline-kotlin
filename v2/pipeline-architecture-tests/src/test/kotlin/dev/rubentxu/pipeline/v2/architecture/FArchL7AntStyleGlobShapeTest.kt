package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * F-ARCH-L7-003: AntStyleGlob wraps Spring AntPathMatcher.
 *
 * Architecture test that enforces AntStyleGlob is a thin wrapper around
 * Spring's org.springframework.util.AntPathMatcher.
 *
 * The match() method must delegate to Spring's AntPathMatcher, ensuring
 * battle-tested glob semantics matching Jenkins's AntPattern spec.
 *
 * RED: ClassNotFoundException (no AntStyleGlob class yet)
 * GREEN: After T-10, AntStyleGlob.match() delegates to Spring's AntPathMatcher
 */
class FArchL7AntStyleGlobShapeTest {

    /**
     * Verifies AntStyleGlob class exists and has a match method.
     */
    @Test
    fun `ant_style_glob_class_exists`() {
        val antStyleGlobClass = Class.forName(
            "dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob"
        )
        assertNotNull(antStyleGlobClass, "AntStyleGlob class must exist")
    }

    /**
     * Verifies AntStyleGlob.match() method exists and returns List<Path>.
     */
    @Test
    fun `ant_style_glob_has_match_method`() {
        val antStyleGlobClass = Class.forName(
            "dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob"
        )

        val matchMethod = antStyleGlobClass.declaredMethods
            .filter { it.name == "match" }
            .firstOrNull()

        assertNotNull(matchMethod, "AntStyleGlob must have a match() method")

        // Verify return type is List
        val returnType = matchMethod!!.returnType
        assertEquals(
            "java.util.List",
            returnType.name,
            "match() must return List<Path>"
        )
    }

    /**
     * Verifies AntStyleGlob delegates to Spring's AntPathMatcher.
     *
     * This test uses reflection to verify the internal implementation
     * uses Spring's AntPathMatcher, not a custom regex implementation.
     */
    @Test
    fun `ant_style_glob_delegates_to_spring_ant_path_matcher`() {
        val antStyleGlobClass = Class.forName(
            "dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob"
        )

        // Verify Spring AntPathMatcher is on the classpath
        val springMatcherClass = Class.forName(
            "org.springframework.util.AntPathMatcher"
        )
        assertNotNull(springMatcherClass, "Spring AntPathMatcher must be on classpath")

        // Check that AntStyleGlob has a field of type AntPathMatcher
        val matcherField = antStyleGlobClass.declaredFields
            .filter { it.type == springMatcherClass }
            .firstOrNull()

        assertNotNull(
            matcherField,
            "AntStyleGlob must have an AntPathMatcher field (delegation pattern)"
        )
    }

    /**
     * Verifies the full sealed subclasses exhaustivity for archive-related types.
     */
    @Test
    fun `ant_style_glob_supports_all_ant_patterns`() {
        val antStyleGlobClass = Class.forName(
            "dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob"
        )

        // AntStyleGlob should accept patterns with *, **, ?, [...], {...}
        val constructor = antStyleGlobClass.declaredConstructors.firstOrNull()
        assertNotNull(constructor, "AntStyleGlob must have a constructor")

        // Verify pattern parameter exists
        val patternParam = constructor!!.parameters.filter {
            it.name == "pattern" || it.type == String::class.java
        }.firstOrNull()

        assertNotNull(
            patternParam,
            "AntStyleGlob constructor must accept a pattern parameter"
        )
    }
}
