plugins {
    kotlin("jvm") version "2.1.20"
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

application { mainClass.set("demo.AppKt") }

tasks.jar {
    manifest { attributes["Main-Class"] = "demo.AppKt" }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("demo.broken", providers.gradleProperty("demo.broken").orElse("false").get())
}
