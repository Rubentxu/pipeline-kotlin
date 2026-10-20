plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-credentials-api"))
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcutil)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Tests cover the EIGHT pure primitives in this module: KdfParams, AeadCipher,
// PassphraseResolver, CredentialsStorePath, CredentialsStorePosix (assertSupported only),
// CredentialCrypto, CredentialSerializer and CredentialPartReader. The store file's
// IO is exercised by the E2E pipelines under `v2/examples/security/`, not by JUnit; the
// AGENTS.md rule against filesystem persistence in unit tests is what scopes the
// suite this way. Each primitive is a `data class` or a pure `object`; the test
// functions take arguments and return values without touching disk, network or clock.
