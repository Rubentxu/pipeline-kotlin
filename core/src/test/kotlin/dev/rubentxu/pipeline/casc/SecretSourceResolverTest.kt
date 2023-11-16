package dev.rubentxu.pipeline.casc


import dev.rubentxu.pipeline.casc.resolver.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.apache.commons.text.StringSubstitutor
import org.apache.commons.text.lookup.StringLookupFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.*


class SecretSourceResolverTest : StringSpec({


    val resolver = SecretSourceResolver()
    val ENCODE  = Base64Lookup.INSTANCE
    val DECODE = StringLookupFactory.INSTANCE.base64DecoderStringLookup();
    val FILE = FileStringLookup.INSTANCE;
    val BINARYFILE = FileBase64Lookup.INSTANCE;
    val SYSPROP = SystemPropertyLookup.INSTANCE;


    beforeTest {

    }
    fun getPath(fileName: String): Path {
        try {
            return Path.of(this::class.java.classLoader.getResource(fileName)?.toURI())
        } catch (e: Exception) {
            return Path.of("")
        }

    }

    "resolve simple variable" {
        val map: Map<String, String> = mutableMapOf("FOO" to "hello")
        System.setProperty("FOO", "hello")
        resolver.resolve("\${PATH}") shouldBe ("hello")
        val result = StringSubstitutor.createInterpolator().replace(
               "OS name: \${sys:os.name}, user: \${env:USER}");

        println(result)
    }

    "resolve sigle entry with default value" {
        resolver.resolve("\${FOO:-default}") shouldBe ("default")
    }

    "resolve base64 nested env" {
        val input = "Hello World"
        val output: String = resolver.resolve("\${base64:$input}")
        output shouldBe ENCODE.lookup(input)
        DECODE.lookup(output) shouldBe input
    }

    "resolve base64 nested env with default value" {
        val input = "Hello World"
        val output: String = resolver.resolve("\${base64:$input:-default}")
        output shouldBe ENCODE.lookup(input)
        DECODE.lookup(output) shouldBe input
    }

    "resolve FIle" {
        val input = getPath("secretResolver/secret.json").toAbsolutePath().toString()
        val output: String = resolver.resolve("\${readFile:$input}")
        println(output)
        output shouldBe FILE.lookup(input)
    }

    "resolve File with relative path" {
        val path = getPath("secretResolver/secret.json")
        val input: String = Path.of("").toUri().relativize(path.toUri()).getPath()
        val output: String = resolver.resolve("\${readFile:$input}")
        println(output)
        output shouldBe FILE.lookup(input)
        output shouldContain "\"Our secret\": \"Hello World\""
    }

    "resolve file key" {
        val path = getPath("secretResolver/secret.key")
        val input: String = Path.of("").toUri().relativize(path.toUri()).getPath()
        val output: String = resolver.resolve("\${readFile:$input}")

        output shouldBe FILE.lookup(input)
        output shouldStartWith  "-----BEGIN RSA PRIVATE KEY-----"
    }

    "resolve File with space" {
        val path = getPath("secretResolver/some secret.json").toAbsolutePath().toString()
        val output: String = resolver.resolve("\${readFile:$path}")

        println(output)
        output shouldBe FILE.lookup(path)
        output shouldContain "\"Our secret\": \"Hello World\""
    }

    "resolve File with space and relative" {
        val path = getPath("secretResolver/some secret.json")
        val input: String = Path.of("").toUri().relativize(path.toUri()).getPath()
        val output: String = resolver.resolve("\${readFile:$input}")

        println(output)
        output shouldBe FILE.lookup(input)
        output shouldContain "\"Our secret\": \"Hello World\""
    }

    "resolve File with space and relative and base64" {
        val path = getPath("secretResolver/some secret.json")
        val input: String = Path.of("").toUri().relativize(path.toUri()).getPath()
        val output: String = resolver.resolve("\${base64:\${readFile:$input}}")

        println(output)
        output shouldBe ENCODE.lookup(FILE.lookup(input)!!)
        output shouldContain "ewogICJPdXIgc2VjcmV0IjogIkhlbGxvIFdvcmxkIgp9"
    }

    "resolve File not found" {
        val path = getPath("secretResolver/notfound.json").toAbsolutePath().toString()
        val output: String = resolver.resolve("\${readFile:$path}")

        println(output)
        output shouldBe ""
    }

    "resolve binary File base64" {
        val path = getPath("secretResolver/secret.json")
        val pathStr = path.toAbsolutePath().toString()

        val bytes = Files.readAllBytes(path)
        val expected: String = Base64.getEncoder().encodeToString(bytes)
        val expectedBytes: ByteArray = Base64.getDecoder().decode(expected)
        val actual: String = resolver.resolve("\${fileBase64:$pathStr}")

        val actualBytes = Base64.getDecoder().decode(actual)
        val lookup = BINARYFILE.lookup(pathStr)

        lookup shouldBe expected
        actual shouldBe expected
        actualBytes shouldBe expectedBytes
    }

    "resolve System Property" {
        val key = "java.version"
        val value = System.getProperty(key)
        val actual: String = resolver.resolve("\${sysProp:$key}")
        val lookup = SYSPROP.lookup(key)

        lookup shouldBe value
        actual shouldBe value
    }

    "resolve System Property not found" {
        val key = "java.version-notfound"

        val actual: String = resolver.resolve("\${sysProp:$key}")
        val lookup = SYSPROP.lookup(key)

        println("lookup: $lookup")
        lookup shouldBe ""
        actual shouldBe ""
    }
})



