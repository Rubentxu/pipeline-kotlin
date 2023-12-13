plugins {
    kotlin("jvm")
    id("io.kotest") version "0.4.10"

}

val kotlinVersion: String by rootProject.extra
val kotlinCoroutinesVersion: String by rootProject.extra
val kotestVersion: String by rootProject.extra
val appVersion: String by rootProject.extra

group = "dev.rubentxu.pipeline.dsl"
version = appVersion





dependencies {
    implementation(project(":pipeline-dsl"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")


    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-property-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-extensions-junit5:$kotestVersion")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("uk.org.webcompere:system-stubs-jupiter:2.1.5")
    testImplementation("io.kotest:kotest-framework-datatest-jvm:5.8.0")


}



java {
    sourceCompatibility = JavaVersion.toVersion("17")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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

tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions
            .languageVersion
            .set(
                org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9
            )
    }