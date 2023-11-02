import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
//import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
plugins {
    kotlin("jvm")
    id("io.kotest") version "0.4.10"
//    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("org.graalvm.buildtools.native") version "0.9.28"
    application
}

val kotlinVersion: String by rootProject.extra
val kotlinCoroutinesVersion: String by rootProject.extra

group = "dev.rubentxu.pipeline.cli"
version = "1.0-SNAPSHOT"


kotlin {
    jvmToolchain(17)
}


dependencies {
    implementation(project(":core"))
//    implementation(project(":pipeline-script"))

    implementation("com.github.ajalt.clikt:clikt:4.2.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.3")
    implementation("org.gradle:gradle-tooling-api:8.4")


    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
//    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
//    implementation("org.jetbrains.kotlin:kotlin-script-util:1.8.22")

//    runtimeOnly("org.jetbrains.kotlin:kotlin-main-kts:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinVersion")

//    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
//    implementation("org.jetbrains.kotlin:kotlin-main-kts:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinCoroutinesVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")

    testImplementation(kotlin("test"))

    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.7.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("io.kotest:kotest-property-jvm:5.7.2")

}

tasks.test {
    useJUnitPlatform()
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.rubentxu.pipeline.cli.PipelineCliKt")
}

tasks {
    val fatJar = register<Jar>("fatJarCustom") {
        dependsOn.addAll(listOf("compileJava", "compileKotlin", "processResources")) // We need this for Gradle optimization to work
        archiveClassifier.set("standalone") // Naming the jar
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest { attributes(mapOf("Main-Class" to application.mainClass)) }
        exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")// Provided we set it up in the application plugin configuration
        val sourcesMain = sourceSets.main.get()
        val contents = configurations.runtimeClasspath.get()
            .map { if (it.isDirectory) it else zipTree(it) } +
                sourcesMain.output
        from(contents)
    }
    build {
        dependsOn(fatJar) // Trigger fat jar creation during build
    }
}
//
//tasks {
//    val shadowJarVal by creating(ShadowJar::class) {
//        archiveClassifier.set("")
//        manifest {
//            attributes["Main-Class"] = application.mainClass.get()
//        }
//        exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
//        from(sourceSets.main.get().output)
//        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
//    }
//
//    named<BuildNativeImageTask>("nativeCompile") {
//        classpathJar.set(shadowJarVal.outputs.files.singleFile)
//    }
//
//    build {
//        dependsOn("nativeCompile")
//    }
//}



graalvmNative {
    toolchainDetection.set(true)
    binaries {
        named("main") {
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(17))
                vendor.set(JvmVendorSpec.matching("Oracle Corporation"))
            })
            useFatJar.set(true)
        }

    }
    binaries.all {
        buildArgs.add("--verbose")
    }
}



tasks.register("printClasspath") {
    doLast {
        configurations["runtimeClasspath"].forEach { println(it) }
    }
}
