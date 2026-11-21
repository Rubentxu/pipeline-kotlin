// `java` resolves to JavaPluginExtension inside a Kotlin DSL script, so these
// imports are what make the unqualified names below mean the JDK's own.
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

// INDEPENDENT BUILD: a separate Gradle boundary, like the example plugins.
//
// It depends ONLY on the published SDK artifact `pipelinek-service-client`
// and on the contracts that artifact brings with it. It does NOT depend on
// `pipeline-application`, and it does NOT reach into the product's internals:
// a client that could see the product's classes would prove nothing about a
// client that has to resolve the SDK like anybody else.
//
// The SDK is resolved as an ordinary Maven coordinate from a build-local
// repository produced by the build from the SAME source revision. It is
// deliberately NOT a `files(...)` dependency on a committed jar: a stale SDK
// must fail this build rather than silently satisfy it.
//   -PsdkRepo=<dir>     repository holding the SDK artifacts
//   -PsdkVersion=<ver>  SDK version to resolve
val sdkRepo: String = providers.gradleProperty("sdkRepo").getOrElse("../../pipelinek/build/sdk-repo")

// No default version, on purpose: a default could only be one that fails to
// resolve, and the failure would name a version nobody asked for.
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required: this example resolves the published SDK and has no default
    version, because a default could only be one that fails to resolve. Pass the candidate's
    version explicitly, e.g. -PsdkVersion=0.99.0
    """.trimIndent()
}

repositories {
    // The SDK is resolved from ONE repository, and from that one ONLY.
    //
    // The product's coordinates are published to Maven Central too, and a
    // version that Central carries is a version Central serves: a build that
    // asked both would take the first answer it got, and the repository under
    // test would stop being what the consumer resolved from. The order of the
    // repositories is not the guarantee; the EXCLUSIVITY is.
    //
    // The exclusivity is what makes a withdrawal mean something: an artifact
    // withdrawn from the repository under test has to be ABSENT for the
    // consumer, and a build that could still ask Central would find it there
    // and would pass for the wrong reason. The group of the SDK is therefore
    // resolved from the repository under test and from nowhere else; every
    // other group —the Kotlin standard library among them— comes from
    // Central, which is where the artifacts that are not the product's live.
    exclusiveContent {
        forRepository {
            maven {
                name = "sdk"
                url = uri(sdkRepo)
            }
        }
        filter {
            includeGroup("dev.rubentxu.pipelinek")
            includeGroup("dev.rubentxu.pipeline.v2")
        }
    }
    mavenCentral()
}

// The version this build resolves is FIXED: the caller names it, and the
// release it names is a release and not a moving target.
//
// The rule the dependencies below declare therefore has to hold on its own
// terms: a stale SDK must fail this build rather than silently satisfy it.
// Two mechanisms pay it, and neither of them is a cache policy:
//
//   - the identity law below, which runs with EVERY build of this example
//     and compares the bytes the build resolved with the digest the
//     repository declares NOW. A resolution that is not the repository's own
//     bytes fails the build: a local copy, a different repository, or a
//     resolution that answers with what a previous build remembered.
//   - the refresh the release gate asks for, which asks the repository again
//     instead of answering out of what a previous build remembered, and so
//     covers the METADATA of the resolution as well as its bytes.
//
// The cache policy of a CHANGING version —a SNAPSHOT, a `-dev`— is not here:
// it applies to a version that changes, and this build resolves one that
// does not. A mechanism that does not apply is not a mechanism.

dependencies {
    // The published BOM is the SDK's single resolution document: ONE platform
    // declaration pins the version of every published contract, so the
    // contract below carries no version of its own.
    implementation(platform("dev.rubentxu.pipelinek:pipelinek-sdk-bom:$sdkVersion"))
    // The client of the local service. ONE dependency is the whole point of
    // the SDK: the wire's vocabulary, the codec and the typed client arrive
    // together, and the consumer writes no copy of any of them.
    implementation("dev.rubentxu.pipelinek:pipelinek-service-client")
    // The step contract, and the human-decision port a step declares to ask a
    // person. A plugin outside this repository writes its steps against these
    // two contracts, and a bundle that did not carry them would not compile
    // this example at all.
    implementation("dev.rubentxu.pipelinek:pipelinek-domain")
    implementation("dev.rubentxu.pipelinek:pipelinek-runtime-api")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("example.serviceclient.ServiceClientExampleKt")
}

// The resolved SDK is an INPUT of the compilation, declared here: a build
// that compiles against an SDK it did not resolve is a build whose artifact
// declares a compatibility it cannot back.
tasks.named("compileKotlin") {
    inputs.files(configurations.named("compileClasspath"))
        .withNormalizer(ClasspathNormalizer::class)
}

// ── the two laws this consumer carries ────────────────────────────────────────
//
// A consumer that resolves the SDK by coordinates proves two things at once, and
// only one of them is about the resolution:
//
//   * that the coordinates name a usable SDK, and
//   * that the SDK a consumer ends up RUNNING with is the one the product
//     publishes, and not the server that produced it.
//
// The second half is invisible in a successful build: a classpath that carries
// the product's own core, or a provider, or Jenkins, compiles and runs exactly
// as well as the right one. So the two facts are stated here, as tasks that
// REFUSE, and the release gate runs them.
//
// The lists below are the CONSUMER's own policy, and not a second authority
// about what the product publishes: this build is independent of the product's
// build, so it cannot read the product's list, and it does not try. What it
// names is what a client of the local service must not carry.

/**
 * The artifacts a client of the local service must not carry.
 *
 * Each entry is a module of the PRODUCT, named the way the product names it.
 * The published spelling of a contract drops the product's prefix
 * (`pipelinek-domain` for `pipeline-domain`), so both spellings are refused.
 *
 * - the application, the durable runtime and the stores: they hold a pipeline's
 *   state, and a client that holds them could answer a question from its own
 *   copy instead of asking the service;
 * - the credential providers and the credential store: a client asks a service
 *   for a credential, and a client that carries the store holds the secret the
 *   service was supposed to keep;
 * - the plugin host and the script toolchain: they belong to the host that
 *   loads a plugin, not to a program that talks to a service.
 */
val forbiddenRuntimeArtifacts: Set<String> = setOf(
    "pipeline-application",
    "pipeline-runtime",
    "pipeline-events-store",
    "pipeline-output-store",
    "pipeline-artefacts-local",
    "pipeline-credentials-api",
    "pipeline-credentials-local",
    "pipeline-credentials-executor",
    "pipeline-credentials-multipart",
    "pipeline-step-sdk",
    "pipeline-scripting-kotlin24",
    "pipeline-event-harness",
    "pipeline-release",
)

/**
 * The groups a client of the local service must not resolve from.
 *
 * Jenkins is named here because the product's own vocabulary cannot be used
 * for it: `jenkins-core` carries no product module name, and a rule that only
 * knew the product's names would let the whole of Jenkins through.
 */
val forbiddenRuntimeGroups: List<String> = listOf(
    "org.jenkins-ci",
    "io.jenkins",
    "org.jenkins-ci.plugins",
)

/**
 * The artifact the consumer talks to the service with.
 *
 * Its digest is the identity of what the consumer ran with: the receipt of the
 * gate names it, so a reader knows which bytes the consumer had without
 * having to look at a directory the gate is about to delete.
 */
val clientArtifactName: String = "pipelinek-service-client"

/**
 * The upstream classes the SDK's published surface names.
 *
 * A consumer that compiles against `ServiceClient` compiles against the
 * observation the runtime publishes and against the cursor the event plane
 * publishes: those types are in the SDK's public signature, and a consumer
 * whose classpath lacks one of them compiles against a contract it cannot
 * honour at run time. The list is what the consumer's own code names, and the
 * task below measures it against the RESOLVED classpath.
 */
val upstreamClassesTheSdkNames: List<String> = listOf(
    "dev.rubentxu.pipeline.v2.domain.RunId",
    "dev.rubentxu.pipeline.v2.events.identity.EventCursor",
    "dev.rubentxu.pipeline.v2.runtime.input.InputDecisions",
    "dev.rubentxu.pipeline.v2.runtime.input.InputDecisionsKt",
    "dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation",
    "dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation",
    "dev.rubentxu.pipeline.v2.service.ServiceAnswer",
    "dev.rubentxu.pipeline.v2.service.ServiceClient",
    "dev.rubentxu.pipeline.v2.service.ServiceProtocol",
    "dev.rubentxu.pipeline.v2.service.ServiceRefusal",
)

/**
 * The digest of the bytes of [file], in hexadecimal.
 *
 * The text is formed by `HexFormat`, and not by a format string: a format
 * string carries the locale of the machine that formats it, and the digest of
 * a file is a fact that does not depend on the machine that measures it.
 */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

/**
 * The name of the module the file [fileName] belongs to.
 *
 * A file on a classpath is named `<module>-<version>.jar`, and the module is
 * what the rest of the name says. The version is what follows the LAST dash,
 * because a module name carries dashes of its own.
 */
fun moduleNameOf(fileName: String): String {
    val base = fileName.removeSuffix(".jar")
    val withoutVersion = base.substringBeforeLast('-')
    return if (withoutVersion.isEmpty()) base else withoutVersion
}

/**
 * The names the module of [fileName] may be known by.
 *
 * The product publishes its contracts under a name that carries the product's
 * prefix, and the module behind that artifact is named without it. A rule
 * that knew one spelling would not see the other, and the artifact it has to
 * refuse would pass.
 */
fun moduleNameCandidatesOf(fileName: String): Set<String> {
    val base = fileName.removeSuffix(".jar")
    return setOf(moduleNameOf(fileName), base.replace("pipelinek-", "pipeline-"))
}

val verifyRuntimeClasspath by tasks.registering {
    group = "verification"
    description = "Refuses a runtime classpath that carries the server, a provider or Jenkins."
    val classpath = configurations.named("runtimeClasspath")
    val forbiddenArtifacts = forbiddenRuntimeArtifacts
    val forbiddenGroups = forbiddenRuntimeGroups
    val requiredClasses = upstreamClassesTheSdkNames
    doLast {
        val configuration = classpath.get()
        // The FILES of the classpath, and not only the artifacts of the
        // resolution: a dependency declared as a file puts bytes on the
        // classpath without appearing in the resolution at all, and a rule that
        // only read the resolution would not see the server arrive that way.
        val files = configuration.files.filter { it.isFile }
        val offenders = mutableListOf<String>()
        configuration.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val group = artifact.moduleVersion.id.group
            if (forbiddenGroups.any { group == it || group.startsWith("$it.") }) {
                offenders.add(artifact.moduleVersion.id.toString())
            }
        }
        files.forEach { file ->
            val names = moduleNameCandidatesOf(file.name)
            if (names.any { it in forbiddenArtifacts }) {
                offenders.add(file.name)
            } else if (file.name.contains("jenkins", ignoreCase = true)) {
                offenders.add(file.name)
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "the runtime classpath carries what a client of the service must not carry: " +
                    offenders.joinToString(", "),
            )
        }

        val requiredEntries = requiredClasses.map { it.replace('.', '/') + ".class" }.toSet()
        val present = mutableSetOf<String>()
        files.filter { it.name.endsWith(".jar") }.forEach { file ->
            ZipFile(file).use { archive ->
                archive.entries().asSequence().forEach { entry ->
                    if (entry.name in requiredEntries) present.add(entry.name)
                }
            }
        }
        val missing = requiredEntries - present
        if (missing.isNotEmpty()) {
            throw GradleException(
                "the runtime classpath does not carry the upstream classes the SDK names: " +
                    missing.joinToString(", "),
            )
        }
        println(
            "consumer: runtime classpath carries ${files.size} file(s), " +
                "none of them the server, a provider or Jenkins, " +
                "and all ${requiredClasses.size} upstream class(es) the SDK names",
        )
    }
}

val verifyResolvedIdentity by tasks.registering {
    group = "verification"
    description = "Checks that the bytes the consumer resolved are the ones the repository declares."
    val classpath = configurations.named("runtimeClasspath")
    val repository = sdkRepo
    val productGroups = setOf("dev.rubentxu.pipelinek", "dev.rubentxu.pipeline.v2")
    val groupPaths = listOf("dev/rubentxu/pipelinek", "dev/rubentxu/pipeline/v2")
    val client = clientArtifactName
    // The file the measurement is written to, when the caller names one. The
    // gate asks for it because the receipt it writes has to carry the
    // measurement without a reader having to look at a directory the gate is
    // about to delete.
    val measurement = providers.gradleProperty("identityMeasurement").orNull
    doLast {
        val root = File(repository)
        val configuration = classpath.get()
        val files = configuration.files.filter { it.isFile }
        val mismatches = mutableListOf<String>()
        // The artifacts are counted ONCE each: the same artifact is measured
        // twice —once as the resolution and once as a file of the classpath—
        // and a count that counted it twice would report a number of
        // measurements where the reader expects a number of artifacts.
        val matched = mutableSetOf<String>()
        var clientDigest: String? = null

        // Every artifact of the SDK that the consumer RESOLVED has to be one the
        // repository carries, with the digest the repository declares.
        configuration.resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group in productGroups }
            .filter { it.file.name.endsWith(".jar") }
            .forEach { artifact ->
                val id = artifact.moduleVersion.id
                val directory = File(root, "${id.group.replace('.', '/')}/${id.name}/${id.version}")
                val published = File(directory, "${id.name}-${id.version}.jar")
                val declared = File(directory, "${id.name}-${id.version}.jar.sha256")
                if (!published.isFile) {
                    mismatches.add("${id.name}: the repository carries no artifact of it")
                    return@forEach
                }
                if (!declared.isFile) {
                    mismatches.add("${id.name}: the repository declares no digest of it")
                    return@forEach
                }
                val declaredDigest = declared.readText().trim()
                val publishedDigest = sha256Of(published)
                val resolvedDigest = sha256Of(artifact.file)
                if (declaredDigest != publishedDigest) {
                    mismatches.add("${id.name}: the digest the repository declares is not the digest of its own bytes")
                }
                if (resolvedDigest != publishedDigest) {
                    mismatches.add("${id.name}: the consumer resolved bytes the repository does not declare")
                }
                matched.add("${id.name}-${id.version}.jar")
                if (id.name == client) {
                    clientDigest = resolvedDigest
                }
            }

        // Every FILE of the classpath that names a published artifact has to BE
        // those bytes: a copy that a build put on the classpath by hand is
        // measured against the same declaration, and a copy that differs from
        // it is refused.
        groupPaths.forEach { groupPath ->
            val groupDirectory = File(root, groupPath)
            if (!groupDirectory.isDirectory) return@forEach
            val artifactDirectories = groupDirectory.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (artifactDirectory in artifactDirectories) {
                val versionDirectories = artifactDirectory.listFiles()?.filter { it.isDirectory } ?: emptyList()
                for (versionDirectory in versionDirectories) {
                    val jars = versionDirectory.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()
                    for (jar in jars) {
                        val declared = File(versionDirectory, jar.name + ".sha256")
                        if (!declared.isFile) {
                            mismatches.add("${jar.name}: the repository declares no digest of it")
                            continue
                        }
                        val declaredDigest = declared.readText().trim()
                        files.filter { it.name == jar.name }.forEach { file ->
                            if (sha256Of(file) != declaredDigest) {
                                mismatches.add(
                                    "${jar.name}: the classpath carries bytes the repository does not declare",
                                )
                            }
                            matched.add(jar.name)
                        }
                    }
                }
            }
        }

        if (mismatches.isNotEmpty()) {
            throw GradleException(
                "the bytes the consumer resolved are not the ones the repository declares:\n  " +
                    mismatches.joinToString("\n  "),
            )
        }
        if (matched.isEmpty()) {
            throw GradleException("the consumer resolved no artifact of the SDK; nothing was measured")
        }
        if (measurement != null) {
            File(measurement).writeText(
                "artifacts_matched=${matched.size}\n" +
                    "client_digest=${clientDigest ?: ""}\n",
            )
        }
        println("consumer: ${matched.size} artifact(s) match the digests the repository declares")
    }
}

// The laws run with EVERY build of this example, and not only when a gate
// asks for them: a consumer that could be built against bytes the repository
// does not declare is a consumer whose own build says nothing about what it
// runs with. The two ways to use this example —the installed program and the
// program Gradle runs— both go through them.
tasks.named("installDist") {
    dependsOn("verifyRuntimeClasspath", "verifyResolvedIdentity")
}

tasks.named("run") {
    dependsOn("verifyRuntimeClasspath", "verifyResolvedIdentity")
}
