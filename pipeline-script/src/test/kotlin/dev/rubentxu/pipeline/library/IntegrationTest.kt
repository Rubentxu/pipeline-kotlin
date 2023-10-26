package dev.rubentxu.pipeline.library

import dev.rubentxu.pipeline.compiler.GradleCompiler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Paths
import java.net.URLClassLoader

class IntegrationTest : StringSpec({
    val gradleCompiler = GradleCompiler()
    val libraryId = LibraryId("testLibrary", "1.0")
    val libraryLoader = LibraryLoader()

    "should load library from local source and call greetings successfully" {
        val libraryConfiguration = LibraryConfiguration(
            name = "testLibrary",
            sourcePath = Paths.get("../integrations/").toString(),
            version = "1.0",
            retriever = LocalSource(gradleCompiler),
            credentialsId = null
        )
        libraryLoader.libraries[libraryId] = libraryConfiguration

        val jarFile = libraryLoader.loadLibrary(libraryId)
        jarFile.exists() shouldBe true

        val url = jarFile.toURI().toURL()
        val urlClassLoader = URLClassLoader(arrayOf(url))
        val myClass = Class.forName("dev.rubentxu.integrations.HelloWorld", true, urlClassLoader)
        val instance = myClass.getDeclaredConstructor().newInstance()

        val greetingsMethod = myClass.getDeclaredMethod("greetings")
        val result = greetingsMethod.invoke(instance)
        println("El resultado es: $result")
        result shouldBe "Expected greeting message"
    }

    // Similar tests for LocalJar and GitSource...
})