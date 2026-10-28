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
    // The store these tests can SEE is a path that does not exist. A unit test
    // must not read the operator's real credential store, and the answer a
    // refusal has to be told apart from has to be the same answer on every
    // machine: on one with a populated store, `credentials list` answers with
    // the listing instead of with "No credentials stored.".
    environment(
        "PIPELINE_CREDENTIALS_STORE",
        layout.buildDirectory.file("credentials-unit-store.bin").get().asFile.absolutePath,
    )
}

// Tests cover the EIGHT pure primitives in this module: KdfParams, AeadCipher,
// PassphraseResolver, CredentialsStorePath, CredentialsStorePosix (assertSupported only),
// CredentialCrypto, CredentialSerializer and CredentialPartReader. The store file's
// IO is exercised by the E2E pipelines under `examples/security/`, not by JUnit; the
// AGENTS.md rule against filesystem persistence in unit tests is what scopes the
// suite this way. Each primitive is a `data class` or a pure `object`; the test
// functions take arguments and return values without touching disk, network or clock.
//
// There is ONE instrument here that is not a primitive: the CLI's own decision
// to refuse a request it does not carry out -- a token its verb does not own,
// or a request that names no verb of this command at all
// (CredentialsRefusalTest). It calls the command in process and reads its exit
// code and its output; it touches neither disk nor network nor clock, because
// the refusal comes before the store is opened.

