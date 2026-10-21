// buildSrc: build-only support code shared by the plugin SDK build scripts.
//
// Scope (B0.2): the three plugin modules (http, scm-git, utilities) used to carry
// three near-identical copies of the provenance-digest walk + framing + exclusion
// logic. A defect fixed in one copy could silently survive in the other two. This
// project hosts ONE implementation of the hashing primitive; the three build
// scripts consume it and keep only their module-specific wiring.
//
// This is build-only: nothing here ships inside a plugin artifact or is on any
// runtime/test classpath of the main build. It is plain Java (no Gradle API, no
// Kotlin DSL) so it cannot pull a new build dependency into the configuration.
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    // The Gradle-level fixture runs a real Gradle build that executes the SAME production class
    // through a real task, hermetically, against `@TempDir`. Bundled with Gradle: no new external
    // dependency, no network.
    testImplementation(gradleTestKit())
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
