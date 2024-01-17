import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinVersion: String by project
val kotlinCoroutinesVersion: String by project
val kotestVersion: String by project
val appVersion: String by project

plugins {
    kotlin("jvm") version "1.9.21"
    id("io.kotest") version "0.4.10"
//    id("com.google.devtools.ksp") version "1.8.21-1.0.11"
}

group = "dev.rubentxu.pipeline.dsl"
version = appVersion


dependencies {
    implementation("org.yaml:snakeyaml:2.2")

    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.1")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    implementation("io.arrow-kt:arrow-core:1.2.1")
    implementation("io.arrow-kt:arrow-fx-coroutines:1.2.1")
    implementation("io.arrow-kt:arrow-optics:1.2.1")
//    implementation("io.arrow-kt:arrow-fx-stm:1.2.1")



//    ksp("io.arrow-kt:arrow-optics-ksp-plugin:1.2.0")


    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-property-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-extensions-junit5:$kotestVersion")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("uk.org.webcompere:system-stubs-jupiter:2.1.5")
    testImplementation("io.kotest:kotest-framework-datatest-jvm:5.8.0")
    testImplementation("io.kotest.extensions:kotest-assertions-arrow:1.4.0")
    testImplementation("io.kotest.extensions:kotest-assertions-arrow-fx-coroutines:1.4.0")
    testImplementation("io.kotest:kotest-property:5.8.0")


}



java {
    sourceCompatibility = JavaVersion.toVersion("17")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn", "-Xcontext-receivers")
        }
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn", "-Xcontext-receivers")
        }
    }

    test {
        useJUnitPlatform()
//        jvmArgs()
    }
}

tasks.withType<Test> {
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn" + "-Xcontext-receivers"
    }
}

tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions
            .languageVersion
            .set(
                org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9
            )
    }