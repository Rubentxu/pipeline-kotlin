plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

// H0 — the harness contracts module.
//
// Deliberately minimal dependencies. The catalogue is DATA plus pure functions over it, so it needs
// no product class, no persistence, no process runner and no test framework at runtime. That is what
// lets a selection run before anything is compiled: if the catalogue needed the product on the
// classpath, impact analysis could not answer "what must be tested" until the product already
// built, which is one moment too late.
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

tasks.test {
    useJUnitPlatform()
}
