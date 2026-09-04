import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // alias(libs.plugins.kotlin.scripting)  // marker no publicado para Kotlin 2.4.10; el runtime se obtiene por libs.bundles.kotlin.scripting en cada módulo que lo necesita.
    alias(libs.plugins.dokka)
}

allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")

    // JVM toolchain and target enforcement for all JVM-capable subprojects
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}

// LFC0-004: root lifecycle tasks are the local-first V2 entry point. The
// included build remains independently invokable with `-p v2` for the inner
// loop, while `./gradlew check` is the repository-level V2 gate.
tasks.named("check") {
    dependsOn(gradle.includedBuild("v2").task(":check"))
}

tasks.named("build") {
    dependsOn(tasks.named("check"))
}
