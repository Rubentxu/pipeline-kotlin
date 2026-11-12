package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The name of the value by default, and the spelling that replaces it.
 *
 * ## The guarantee
 *
 * `defaultValue` is the name the value by default has, because that is the name
 * Jenkins gives it. The historical spelling (`default`) still declares the same
 * value, and when both arrive the new one wins.
 *
 * ## Why the instrument is this cheap
 *
 * A declaration is plain data, so the question is a call over it: what the
 * author writes, and what the run receives. The rows write both spellings and
 * read what the declaration carries, so a spelling that stopped working, or one
 * that won when it should not, would fail here.
 *
 * ## Why the types are rows of their own
 *
 * Because a boolean carries a conversion the other two do not — it travels as
 * the TEXT of the boolean it is — and a choice carries a closed set of texts.
 * A row that measured the three at once would stay green while one of them
 * lost its value.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ParametersDefaultValueTest {

    @Test
    fun `the new name declares the value by default`() {
        val spec = pipeline {
            parameters {
                string("target", defaultValue = "staging")
            }
        }

        assertEquals("staging", spec.parameters.single().defaultValue)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the historical spelling declares the same value`() {
        val spec = pipeline {
            parameters {
                string("target", default = "staging")
            }
        }

        assertEquals("staging", spec.parameters.single().defaultValue)
    }

    @Test
    fun `the positional form declares the value by default`() {
        val spec = pipeline {
            parameters {
                string("target", "staging")
            }
        }

        assertEquals("staging", spec.parameters.single().defaultValue)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `when both spellings arrive the new one wins`() {
        val spec = pipeline {
            parameters {
                string("target", default = "old", defaultValue = "new")
            }
        }

        assertEquals("new", spec.parameters.single().defaultValue)
    }

    @Test
    fun `the boolean takes the same name`() {
        val spec = pipeline {
            parameters {
                boolean("dryRun", defaultValue = true)
            }
        }

        assertEquals("true", spec.parameters.single().defaultValue)
    }

    @Test
    fun `the choice takes the same name`() {
        val spec = pipeline {
            parameters {
                choice("mode", values = listOf("fast", "safe"), defaultValue = "safe")
            }
        }

        assertEquals("safe", spec.parameters.single().defaultValue)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the boolean keeps the historical spelling`() {
        val spec = pipeline {
            parameters {
                boolean("dryRun", default = true)
            }
        }

        assertEquals("true", spec.parameters.single().defaultValue)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the choice keeps the historical spelling`() {
        val spec = pipeline {
            parameters {
                choice("mode", values = listOf("fast", "safe"), default = "safe")
            }
        }

        assertEquals("safe", spec.parameters.single().defaultValue)
    }
}
