plugins {
    // `java-platform` and NOT `kotlin("jvm")`. This module compiles nothing: it is a version
    // constraint document, and giving it a Kotlin plugin would give it a jar, an ABI and a surface
    // it does not have.
    `java-platform`
    `maven-publish`
}

group = "dev.rubentxu.pipeline.v2"

// ── B2A: why a BOM exists here, and why it is NOT a fifth contract ────────────────────────────
//
// Before this module, an external consumer had to name every published coordinate with its version
// repeated four times:
//
//     implementation("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
//     implementation("dev.rubentxu.pipeline.v2:pipeline-events:$sdkVersion")
//     implementation("dev.rubentxu.pipeline.v2:pipeline-output:$sdkVersion")
//     implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")
//
// A BOM lets the same consumer write the four coordinates once and pin them from a single version:
//
//     implementation(platform("dev.rubentxu.pipeline.v2:pipeline-sdk-bom:$sdkVersion"))
//     implementation("dev.rubentxu.pipeline.v2:pipeline-domain")
//     implementation("dev.rubentxu.pipeline.v2:pipeline-events")
//     implementation("dev.rubentxu.pipeline.v2:pipeline-output")
//     implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api")
//
// That is convenience of RESOLUTION, and it is the whole of what this module does.
//
// The distinction that matters: a contract is a set of TYPES a consumer compiles against and whose
// bytes are guarded by `apiCheck`. `publishedContractModules` in the root build remains the single
// authority for that set, and this BOM is deliberately NOT on it — adding it there would be a claim
// that it has an ABI to freeze, which it does not. The BOM has no `src/`, no classes, no
// `pipeline-sdk-bom.api` and no BCV entry. Its only content is the four constraints below, and every
// version it names is `rootProject.version`, so it cannot drift from the artifacts it pins.
//
// Consequence to keep in mind: a BOM is not a fifth surface to certify — it is a resolution
// convenience that happens to be published alongside the four. Nothing a consumer resolves through
// it that it could not resolve by naming the coordinates directly.
//
// A module joins this set by being published AND being one of the four contract coordinates below.
// The set does NOT include `:pipeline-output-store` or `:pipeline-events-store`: those are not
// published at all (see their build scripts), so a BOM could not name them even by accident.
dependencies {
    constraints {
        // `api`, not `runtime`: a constraint in the `api` scope is what a consumer's
        // `implementation(platform(...))` sees on its COMPILE classpath. A `runtime` constraint
        // would pin versions for a consumer that never compiles against them, which is the exact
        // shape of an accidentally-unversioned compile dependency.
        api("dev.rubentxu.pipeline.v2:pipeline-domain:${rootProject.version}")
        api("dev.rubentxu.pipeline.v2:pipeline-scripting-api:${rootProject.version}")
        api("dev.rubentxu.pipeline.v2:pipeline-events:${rootProject.version}")
        api("dev.rubentxu.pipeline.v2:pipeline-output:${rootProject.version}")
    }
}

// The same build-local Maven repository the four contracts publish to. Not a second mechanism:
// `publishSdkForExternalPlugin` in the root build depends on this module's
// `publishSdkPublicationToSdkRepository` alongside the four, so a consumer resolving `sdk-repo`
// finds the BOM and the contracts produced by the SAME revision.
publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["javaPlatform"])
        }
    }
    repositories {
        maven {
            name = "sdk"
            url = uri(rootProject.layout.buildDirectory.dir("sdk-repo"))
        }
    }
}
