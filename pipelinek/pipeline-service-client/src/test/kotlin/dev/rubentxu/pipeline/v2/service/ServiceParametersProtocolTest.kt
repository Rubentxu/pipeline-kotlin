package dev.rubentxu.pipeline.v2.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The parameters of a run, on the wire.
 *
 * ## The guarantee under trial
 *
 * That the values a client delivers and the declaration it knows travel together, that a
 * line which carries a shape the protocol does not admit is a malformed line, and that the
 * refusal about the parameters comes back with its name.
 *
 * ## Why the DECLARATION travels
 *
 * Because the service never compiles a script to learn what it declares: compiling it would
 * run the author's code inside the service. The declaration comes from the manifest an
 * operator approved for the script, a description hands it to the client, and the client
 * hands it back with the start, so the service checks the values against DATA. The
 * authority is still the declaration of the script: the child verifies the values against
 * it before the body runs.
 *
 * A declaration with a blank name, type or choice is a shape no parameter of the domain can
 * carry, so the line that carries one is malformed, and never an exception.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServiceParametersProtocolTest {

    @Test
    fun `a request to start a run carries the parameters it was given`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"parameters":{"target":"prod","dryRun":"false"}}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val start = assertInstanceOf(ServiceRequest.StartRun::class.java, request)
        assertEquals(
            mapOf("target" to "prod", "dryRun" to "false"),
            start.parameters,
            "the values travel with the request",
        )
    }

    @Test
    fun `a request without parameters carries none`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val start = assertInstanceOf(ServiceRequest.StartRun::class.java, request)
        assertEquals(emptyMap<String, String>(), start.parameters)
        assertTrue(start.parameterContract.isEmpty())
    }

    @Test
    fun `the declaration of the parameters travels with the values`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"parameters":{"target":"prod"},""" +
                """"parameterContract":[{"name":"target","type":"string","defaultValue":"staging"},""" +
                """{"name":"mode","type":"choice","choices":["fast","safe"]}]}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val start = assertInstanceOf(ServiceRequest.StartRun::class.java, request)
        assertEquals(2, start.parameterContract.size)
        assertEquals("target", start.parameterContract[0].name)
        assertEquals("string", start.parameterContract[0].type)
        assertEquals("staging", start.parameterContract[0].defaultValue)
        assertEquals("mode", start.parameterContract[1].name)
        assertEquals("choice", start.parameterContract[1].type)
        assertEquals(listOf("fast", "safe"), start.parameterContract[1].choices)
    }

    @Test
    fun `a request that carries the parameters and the declaration is encoded back the same`() {
        val request = ServiceRequest.StartRun(
            schema = ServiceProtocol.SCHEMA,
            script = "build.kts",
            workspace = null,
            idempotencyKey = null,
            configuration = emptyMap(),
            parameters = mapOf("target" to "prod"),
            parameterContract = listOf(
                dev.rubentxu.pipeline.v2.domain.ParameterSpec("target", "string", "staging"),
            ),
        )

        val decoded = ServiceCodec.decode(ServiceCodec.encode(request))
        val decodedRequest = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val start = assertInstanceOf(ServiceRequest.StartRun::class.java, decodedRequest)
        assertEquals(mapOf("target" to "prod"), start.parameters)
        assertEquals("target", start.parameterContract.single().name)
        assertEquals("staging", start.parameterContract.single().defaultValue)
    }

    @Test
    fun `a parameters field that is not an object is a malformed line`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"parameters":5}""",
        )

        val refused = assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
        assertEquals(ServiceRefusal.MalformedRequest, refused.refusal)
    }

    @Test
    fun `a declaration that is not an array is a malformed line`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"parameterContract":{"name":"target"}}""",
        )

        val refused = assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
        assertEquals(ServiceRefusal.MalformedRequest, refused.refusal)
    }

    @Test
    fun `a declaration whose type is not a primitive is a malformed line`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"parameterContract":[{"name":"target","type":{"kind":"string"}}]}""",
        )

        val refused = assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
        assertEquals(ServiceRefusal.MalformedRequest, refused.refusal)
    }

    @Test
    fun `a declaration whose type is a number is read as the text of that number`() {
        // The house reading of a primitive: a number is the text of that number. Whether
        // the type is one the product knows is a RULE, and the rule belongs to the service,
        // which refuses it by name.
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"parameterContract":[{"name":"target","type":5}]}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val start = assertInstanceOf(ServiceRequest.StartRun::class.java, request)
        assertEquals("5", start.parameterContract.single().type)
    }

    @Test
    fun `the published request does not print the values of the parameters`() {
        val request = ServiceRequest.StartRun(
            schema = ServiceProtocol.SCHEMA,
            script = "build.kts",
            workspace = null,
            idempotencyKey = null,
            configuration = emptyMap(),
            parameters = mapOf("target" to "prod"),
            parameterContract = emptyList(),
        )

        val published = request.toString()

        assertFalse(published.contains("prod"), "the values do not travel in a diagnostic")
        assertTrue(published.contains("parameters=1 values"), "the shape of the request does")
    }

    @Test
    fun `the refusal of the parameters comes back with its name and its reason`() {
        val line = ServiceCodec.encode(
            ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                ServiceRefusal.ParametersRefused(
                    "the-parameter-has-the-wrong-type",
                    "the value of the parameter 'dryRun' does not fit the type 'boolean'",
                ),
            ),
        )

        val decoded = ServiceCodec.decodeLine(line)
        val refused = assertInstanceOf(ServiceLine.Refused::class.java, decoded)
        val parameters = assertInstanceOf(ServiceRefusal.ParametersRefused::class.java, refused.refusal)
        assertEquals("the-parameter-has-the-wrong-type", parameters.reason)
        assertTrue(parameters.detail.contains("dryRun"))
    }

    @Test
    fun `a request to describe the parameters is read as such`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"describe.parameters","script":"build.kts"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val describe = assertInstanceOf(ServiceRequest.DescribeParameters::class.java, request)
        assertEquals("build.kts", describe.script)
    }

    @Test
    fun `a request to describe the parameters without a script is a malformed line`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"describe.parameters"}""",
        )

        val refused = assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
        assertEquals(ServiceRefusal.MalformedRequest, refused.refusal)
    }

    @Test
    fun `a declaration with a blank name, type or choice is a malformed line, and not an exception`() {
        for (entry in listOf(
            """{"name":" ","type":"string"}""",
            """{"name":"target","type":""}""",
            """{"name":"mode","type":"choice","choices":["fast",""]}""",
        )) {
            val decoded = ServiceCodec.decode(
                """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                    """"parameterContract":[$entry]}""",
            )

            val refused = assertInstanceOf(ServiceDecoding.Refused::class.java, decoded, "the entry $entry")
            assertEquals(ServiceRefusal.MalformedRequest, refused.refusal, "the entry $entry")
        }
    }

    @Test
    fun `the refusal of a description has a name of its own`() {
        // The refusal of a DESCRIPTION is not the refusal of the VALUES: the
        // values are fixed and sent again, and a description the service
        // cannot produce is asked of the operator, who approves a manifest.
        // One word for both would make the client read the reason to tell
        // them apart.
        val line = ServiceCodec.encode(
            ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                ServiceRefusal.DescribeParametersRefused(
                    "the-script-has-no-approved-manifest",
                    "there is no approved manifest 'build.kts.parameters.json' next to the script",
                ),
            ),
        )

        val decoded = ServiceCodec.decodeLine(line)
        val refused = assertInstanceOf(ServiceLine.Refused::class.java, decoded)
        val description = assertInstanceOf(
            ServiceRefusal.DescribeParametersRefused::class.java,
            refused.refusal,
        )
        assertEquals("the-script-has-no-approved-manifest", description.reason)
        assertTrue(description.detail.contains("build.kts.parameters.json"), "the detail travels whole")
    }
}
