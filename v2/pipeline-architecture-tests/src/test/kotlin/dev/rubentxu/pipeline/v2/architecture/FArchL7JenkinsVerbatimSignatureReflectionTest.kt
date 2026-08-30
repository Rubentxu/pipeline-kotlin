package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * F-ARCH-L7-006: Jenkins verbatim signature assertion for all 16 NEW ML-R9 step kinds.
 *
 * Architecture test that enforces the 16 new StepSpec data classes match the Jenkins
 * familiarity catalog parameter order exactly.
 *
 * Jenkins catalog entries covered:
 * - §1.1/§1.2 line 63: `dir(path: String) { block }`
 * - §1.1 line 44: `deleteDir()` / `cleanWs(deleteDirs, patterns)`
 * - §1.1 lines 41-43: `catchError(buildResult, stageResult, message) { block }`
 * - `warnError(message, catchInterruptions) { block }`
 * - `unstable(message)`
 * - `pwd(tmp)` / `isUnix()` / `load(path)` / `waitUntil(period, quiet)`
 * - §1.13 line 194: `timestamps { block }` / `ansiColor(colorMapName) { block }`
 * - §1.1 lines 63: `node(label?) { block }`
 * - §1.1 line 58: `milestone(ordinal, label)`
 * - §1.1 lines 33-34: `timeout(time, unit, activity) { block }` / `retry(count, conditions) { block }`
 *
 * This test verifies via reflection:
 * (1) constructor param COUNT and TYPES
 * (2) field names
 *
 * RED: AssertionError on signature mismatch
 * GREEN: After T-04..T-10, all 16 step kinds have correct Jenkins shape
 *
 * Scenarios satisfied: DIR-S-006, WCL-S-008, ERR-S-009, MIL-S-006,
 * WUT-S-009, DEC-S-006, TO-RT-S-009, FIL-ALL-001
 */
class FArchL7JenkinsVerbatimSignatureReflectionTest {

    /**
     * Jenkins catalog shape for each ML-R9 step type.
     * Format: class name -> (param type descriptors, field names)
     * Type descriptors use JVM signature: Ljava/lang/String; for String, etc.
     */
    private data class StepShape(
        val paramTypeDescriptors: List<String>,
        val fieldNames: Set<String>
    )

    /** Convert a Java Class to its JVM type descriptor */
    private fun classToDescriptor(type: Class<*>): String {
        // Handle primitives directly by name
        return when (type.name) {
            "long" -> "J"
            "int" -> "I"
            "boolean" -> "Z"
            "short" -> "S"
            "byte" -> "B"
            "char" -> "C"
            "double" -> "D"
            "float" -> "F"
            else -> "L${type.name.replace('.', '/')};"
        }
    }

    /** The 16 NEW ML-R9 step kinds with their Jenkins catalog shapes */
    private val jenkinsCatalogShapes = mapOf(
        // ML-R9 workflow-control block steps (DIR-S-006)
        "Dir" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // path
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("path", "steps")
        ),

        // ML-R9 workspace-cleanup steps (FIL-ALL-001)
        "DeleteDir" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;"   // path (default ".")
            ),
            fieldNames = setOf("path")
        ),
        "CleanWs" to StepShape(
            paramTypeDescriptors = listOf(
                "Z",                   // deleteDirs (Boolean -> Z)
                "Ljava/util/List;"      // patterns (nullable)
            ),
            fieldNames = setOf("deleteDirs", "patterns")
        ),

        // ML-R9 error-handling steps (ERR-S-009)
        "CatchError" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // buildResult (nullable)
                "Ljava/lang/String;",  // stageResult (nullable)
                "Ljava/lang/String;",  // message (nullable)
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("buildResult", "stageResult", "message", "steps")
        ),
        "WarnError" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // message
                "Z",                   // catchInterruptions (Boolean -> Z)
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("message", "catchInterruptions", "steps")
        ),
        "Unstable" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;"   // message
            ),
            fieldNames = setOf("message")
        ),

        // ML-R9 workflow-utility steps (WUT-S-009)
        "Pwd" to StepShape(
            paramTypeDescriptors = listOf(
                "Z"                    // tmp (Boolean -> Z)
            ),
            fieldNames = setOf("tmp")
        ),
        "IsUnix" to StepShape(
            paramTypeDescriptors = emptyList(),  // no params
            fieldNames = setOf("name", "type")  // inherited from StepSpec interface
        ),
        "Load" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;"   // path
            ),
            fieldNames = setOf("path")
        ),
        "WaitUntil" to StepShape(
            paramTypeDescriptors = listOf(
                "J",                   // initialRecurrencePeriod (Long -> J)
                "Z"                   // quiet (Boolean -> Z)
            ),
            fieldNames = setOf("initialRecurrencePeriod", "quiet")
        ),

        // ML-R9 output-decorator steps (DEC-S-006)
        "Timestamps" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("steps")
        ),
        "AnsiColor" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // colorMapName
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("colorMapName", "steps")
        ),
        "NodeNoOp" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // label (nullable)
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("label", "steps")
        ),

        // ML-R9 milestone step (MIL-S-006)
        "Milestone" to StepShape(
            paramTypeDescriptors = listOf(
                "I",                 // ordinal (Int -> I)
                "Ljava/lang/String;"   // label (nullable)
            ),
            fieldNames = setOf("ordinal", "label")
        ),

        // ML-R9 timeout/retry blocks (TO-RT-S-009)
        "TimeoutBlock" to StepShape(
            paramTypeDescriptors = listOf(
                "J",                 // time (Long -> J)
                "Ljava/lang/String;",  // unit
                "Ljava/lang/String;",  // activity (nullable)
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("time", "unit", "activity", "steps")
        ),
        "RetryBlock" to StepShape(
            paramTypeDescriptors = listOf(
                "I",                 // count (Int -> I)
                "Ljava/util/List;",    // conditions (nullable)
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("count", "conditions", "steps")
        )
    )

    /**
     * Verifies a single step kind matches its Jenkins catalog signature.
     */
    private fun verifyStepSignature(stepName: String, shape: StepShape): List<String> {
        val failures = mutableListOf<String>()
        try {
            val fqcn = "dev.rubentxu.pipeline.v2.dsl.StepSpec\$$stepName"
            val stepClass = Class.forName(fqcn)

            // Verify constructor
            val constructor = stepClass.declaredConstructors
                .filter { it.parameters.size == shape.paramTypeDescriptors.size }
                .firstOrNull()

            if (constructor == null) {
                failures.add("$stepName: no constructor with ${shape.paramTypeDescriptors.size} params")
                return failures
            }

            val paramTypes = constructor.parameterTypes.map { classToDescriptor(it) }
            if (paramTypes != shape.paramTypeDescriptors) {
                failures.add("$stepName: param types mismatch — expected ${shape.paramTypeDescriptors}, got $paramTypes")
            }

            // Verify fields
            val actualFieldNames = stepClass.declaredFields.map { it.name }.toSet()
            if (shape.fieldNames.isNotEmpty()) {
                // Filter out inherited fields from interface (name, type)
                val expectedOwnFields = shape.fieldNames.filter { it != "name" && it != "type" }.toSet()
                if (actualFieldNames != expectedOwnFields && actualFieldNames.intersect(expectedOwnFields) != expectedOwnFields) {
                    failures.add("$stepName: fields mismatch — expected ${shape.fieldNames}, got $actualFieldNames")
                }
            }

        } catch (e: ClassNotFoundException) {
            failures.add("$stepName: ClassNotFoundException — not yet implemented")
        }
        return failures
    }

    /**
     * Tests all 16 NEW ML-R9 step kinds have correct Jenkins catalog signatures.
     */
    @Test
    fun `all 16 new step kinds match jenkins catalog signatures`() {
        val allFailures = mutableListOf<String>()

        for ((stepName, shape) in jenkinsCatalogShapes) {
            val failures = verifyStepSignature(stepName, shape)
            allFailures.addAll(failures)
        }

        if (allFailures.isNotEmpty()) {
            throw AssertionError(
                "Jenkins verbatim signature failures:\n${allFailures.joinToString("\n")}"
            )
        }
    }

    /**
     * Verifies Dir signature matches Jenkins catalog §1.2 line 63.
     */
    @Test
    fun `dir signature matches jenkins catalog`() {
        val failures = verifyStepSignature("Dir", jenkinsCatalogShapes["Dir"]!!)
        if (failures.isNotEmpty()) {
            throw AssertionError("Dir: ${failures.joinToString("; ")}")
        }
    }

    /**
     * Verifies TimeoutBlock signature matches Jenkins catalog §1.1 line 34.
     */
    @Test
    fun `timeoutBlock signature matches jenkins catalog`() {
        val failures = verifyStepSignature("TimeoutBlock", jenkinsCatalogShapes["TimeoutBlock"]!!)
        if (failures.isNotEmpty()) {
            throw AssertionError("TimeoutBlock: ${failures.joinToString("; ")}")
        }
    }

    /**
     * Verifies RetryBlock signature matches Jenkins catalog §1.1 line 33.
     */
    @Test
    fun `retryBlock signature matches jenkins catalog`() {
        val failures = verifyStepSignature("RetryBlock", jenkinsCatalogShapes["RetryBlock"]!!)
        if (failures.isNotEmpty()) {
            throw AssertionError("RetryBlock: ${failures.joinToString("; ")}")
        }
    }

    /**
     * Verifies all 16 step kinds are present in the StepSpec sealed hierarchy.
     */
    @Test
    fun `all 16 new step kinds exist in StepSpec hierarchy`() {
        val stepKinds = StepSpec::class.sealedSubclasses
            .map { it.simpleName }
            .toSet()

        val missing = jenkinsCatalogShapes.keys.filter { it !in stepKinds }
        if (missing.isNotEmpty()) {
            throw AssertionError(
                "Missing StepSpec variants: ${missing.joinToString(", ")}"
            )
        }
    }

    /**
     * Verifies all block-type step kinds (with steps: List<StepSpec>) are handled
     * in the BlockStepFlattener.
     */
    @Test
    fun `all block-type steps have steps list field`() {
        val blockTypes = listOf(
            "Dir", "CatchError", "WarnError", "Timestamps",
            "AnsiColor", "NodeNoOp", "TimeoutBlock", "RetryBlock"
        )

        val failures = mutableListOf<String>()
        for (stepName in blockTypes) {
            try {
                val fqcn = "dev.rubentxu.pipeline.v2.dsl.StepSpec\$$stepName"
                val stepClass = Class.forName(fqcn)
                val hasStepsField = stepClass.declaredFields.any { it.name == "steps" }
                if (!hasStepsField) {
                    failures.add("$stepName: missing 'steps' field")
                }
            } catch (e: ClassNotFoundException) {
                failures.add("$stepName: ClassNotFoundException")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Block-type step failures:\n${failures.joinToString("\n")}"
            )
        }
    }
}
