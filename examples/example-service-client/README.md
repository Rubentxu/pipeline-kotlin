# example-service-client

A consumer OUTSIDE this repository. It resolves the published SDK by its
coordinates, compiles against it, and asks a service about a run.

## The example

`src/main/kotlin/example/serviceclient/ServiceClientExample.kt` is the whole
program. It asks the service for its version, asks it about a run, and prints
one `QUERY` line per question:

```
QUERY version schema=pipelinek.service/2 product=0.98.0
QUERY run id=<runId> state=terminal outcome=succeeded
```

A question the service refuses is written to the standard error and the
program ends with a non-zero code, so a refusal is never read as an answer.

The program resolves the SDK and nothing else: no project of this tree, no
composite build, no jar of this tree. `build.gradle.kts` takes the repository
and the version as properties, because a default could only be one that fails
to resolve, and it declares the group of the SDK as EXCLUSIVE to that
repository — the product's coordinates are on Maven Central too, and a build
that could ask both would not be measuring the repository under test.

## Which version it resolves

The version of the PipelineK SDK, and only that one: the coordinates carry the
version of the contract they publish, and the example asks for it by name.

The version of the Fabric product is a version of its own line, and this
example does not name it. The two lines move at their own pace, and a consumer
that resolves the SDK has no way to name the other one: the Fabric version
belongs to the Fabric release, and the SDK version to the contract.

## The temporary repository

The repository the example resolves from is a Maven repository with the layout
of a remote one, and it is TEMPORARY: a directory under a temporary
directory, produced from the revision under test. It holds the eight published
coordinates of the SDK — the BOM and the seven contracts — with the POM, the
JAR, the Gradle module metadata and the digests beside each of them.

Reproducing it, from the root of this repository:

```
./pipelinek/gradlew -p pipelinek :publishSdkForExternalPlugin
```

The artifacts of the SDK come from that directory and from nowhere else. The
artifacts that are not the product's — the Kotlin standard library and the
serialization runtime among them — come from Maven Central, which is where
the artifacts that are not the product's live. The resolution is reproducible
with the artifacts that are available: the same revision and the same
repository produce the same coordinates and the same digests.

## What the build refuses

The version is a fixed one, and a fixed version is not a moving target: the
build asks the repository for it and the repository answers with the bytes it
publishes.

The build also refuses a resolution that is not the repository's own bytes.
Every build of this example compares the bytes it resolved with the digest the
repository declares NOW, and fails when they differ: a local copy of the SDK,
a resolution that answered from another repository, or one that answered with
what a previous build remembered. To ask the repository again about the
metadata of the resolution as well as about its bytes, pass
`--refresh-dependencies`; the release gate does.

## The gate

`scripts/release/verify-external-consumer.sh` runs this example as a release
gate. It publishes the SDK of the revision into a temporary repository, copies
the example into an isolated directory, resolves it there, compiles it, hosts
a service with this product's own launcher, asks it a real question about a
real run, and pays two negatives: the same consumer against an empty
repository, and the same consumer against a repository whose client artifact
has been withdrawn. Both have to fail.

## The remote publication

The example resolves from a temporary repository. Publishing the SDK to a
REMOTE repository is a decision with an authorization, and this example does
not take it.
