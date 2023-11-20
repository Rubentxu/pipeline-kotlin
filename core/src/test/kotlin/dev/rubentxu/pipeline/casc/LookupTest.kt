package dev.rubentxu.pipeline.casc

import dev.rubentxu.pipeline.extensions.LookupException
import dev.rubentxu.pipeline.extensions.lookup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe


class LookupTest : FunSpec({
    test("should handle escaped strings correctly") {
        val input = "^\${escapedValue}"
        val result = input.lookup().getOrThrow()
        result shouldBe "escapedValue"
    }

    test("should return strings without unchanged markers") {
        val input = "regularString"
        val result = input.lookup().getOrThrow()
        result shouldBe "regularString"
    }

    test("must handle system lookup correctly") {
        val key = "\${sysProp:line.separator}"

        val result = key.lookup().getOrThrow()
        result shouldBe "\n"
    }

    test("should return success with value for existing system property") {
        val key = "\${sysProp:user.home}"
        val expected = System.getProperty("user.home")
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    test("should return failure with LookupException for non-existent system property") {
        val key = "\${sysProp:NON_EXISTENT_PROPERTY}"
        val expected = System.getProperty("NON_EXISTENT_PROPERTY")

        val exception = shouldThrow<LookupException> {
            key.lookup().getOrThrow()
        }
        exception.message shouldBe "Error in system property lookup LookupException System property not found for key: NON_EXISTENT_PROPERTY"
    }

    test("should return success with value for existing environment variable") {
        val key = "\${env:PATH}"
        val expected = System.getenv("PATH")
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    test("should return failure with LookupException for non-existent environment variable") {
        val key = "\${env:NON_EXISTENT_VAR}"
        val expected = System.getenv("NON_EXISTENT_VAR")

        val exception = shouldThrow<LookupException> {
            key.lookup().getOrThrow()
        }
        exception.message shouldBe "Error in environment variable lookup LookupException Environment variable not found for key: NON_EXISTENT_VAR"
    }


    test("should return error for unknown prefixes") {
        val input = "\${unknownPrefix}"
        val exception = shouldThrow<LookupException> {
            input.lookup().getOrThrow()
        }
        exception.message shouldBe "Unknown lookup type for key: unknownPrefix"
    }

    // Añadir más tests para fileLookup,
    test("should handle file lookup correctly") {
        val key = "\${file:src/test/resources/lookup/lookup-example.txt}"
        val expected = "Hola Mundo."
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    // base64Lookup,
    test("should handle base64 lookup correctly") {
        val key = "\${base64:\${base64:Hola Mundo}}"
        val expected = "U0c5c1lTQk5kVzVrYnc9PQ=="
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    test("should handle composite base64 with file lookup correctly") {
        val key = "\${base64:\${file:src/test/resources/lookup/lookup-example.txt}}"
        val expected = "SG9sYSBNdW5kby4="
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    test("should handle composite base64 with readFile lookup correctly") {
        val key = "\${base64:\${readFile:src/test/resources/lookup/lookup-example.txt}}"
        val expected = "SG9sYSBNdW5kby4="
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    test("sholud handle composite decodeBase64 with readFile lookup correctly") {
        val key = "\${decodeBase64:\${readFile:src/test/resources/lookup/fileBase64.txt}}"
        val expected = "Hola Mundo"
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    test("should handle lookup on base64 error") {
        val key = "\${base64:Programación}"

        val expected = "UHJvZ3JhbWFjacOzbg=="
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    // fileBase64Lookup
    test("should handle base64 file lookup correctly") {
        val key = "\${fileBase64:src/test/resources/lookup/lookup-example.txt}"
        val expected = "SG9sYSBNdW5kby4="
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    test("should handle lookup on base64 file error") {
        val key = "\${fileBase64:src/test/resources/lookup/notfound.txt}"
        val exception = shouldThrow<LookupException> {
            key.lookup().getOrThrow()
        }
        exception.message shouldBe "Error in file base64 lookup: NoSuchFileException src/test/resources/lookup/notfound.txt"
    }


    // decodeBase64Lookup
    test("should return success with decoded string from base64") {
        val key = "\${decodeBase64:SG9sYSBNdW5kbw==}"
        val expected = "Hola Mundo"
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }


    test("should return failure with LookupException for invalid base64 string") {
        val invalidBase64 = "!!invalid!!base64!!"
        val key = "\${decodeBase64:!!invalid!!base64!!}"

        val exception = shouldThrow<LookupException> {
            key.lookup().getOrThrow()
        }
        exception.message shouldBe "Error in base64 lookup IllegalArgumentException Invalid symbol '!'(41) at index 0"
    }

    test("should return success with value for existing JSON field") {
        val jsonFieldName = "name"
        val jsonValue = "John Doe"
        val json = """{"$jsonFieldName":"$jsonValue"}"""
        val key = "\${json:$jsonFieldName:$json}"

        val result = key.lookup().getOrThrow()

        result shouldBe jsonValue
    }

    test("should return failure with LookupException for non-existent JSON field") {
        val jsonFieldName = "name"
        val nonExistentField = "age"
        val jsonValue = "John Doe"
        val json = """{"$jsonFieldName":"$jsonValue"}"""
        val key = "\${json:$nonExistentField:$json}"

        val exception = shouldThrow<LookupException> {
            key.lookup().getOrThrow()
        }
        exception.message shouldBe "Error in JSON field lookup LookupException JSON does not contain the specified key '$nonExistentField'"
    }

    test("should return success with new line between tokens for existing JSON field ") {
        val input = "{ \n \"a\": 1, \n \"b\": 2 }"
        val key = "\${json:a:$input}"
        val expected = "1"
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

    test("should return success with new line in value for existing JSON field ") {
        val input = "{ \"a\": \"hello\\nworld\", \"b\": 2 }"
        val key = "\${json:a:$input}"
        val expected = "hello\nworld"
        val result = key.lookup().getOrThrow()
        result shouldBe expected
    }

})