// The COMPOSITE that builds the four external example plugins.
//
// Each of those four plugins is a STANDALONE Gradle build on purpose: it is the
// ROOT of its own build, because the claim it certifies is that the published
// contracts are reachable from OUTSIDE the host build, through the published
// SDK, with nothing of the host build on its classpath. This composite does not
// change that property: it INCLUDES the four standalone builds exactly as they
// are and asks for their `jar` task.
//
// Four separate builds, invoked one after another, pay four times for what one
// invocation pays once: four client starts, four daemon negotiations, and —
// because they are separate builds — no concurrency between them. Included
// builds of a composite are independent projects of ONE build, so Gradle builds
// them CONCURRENTLY, inside ONE Gradle invocation.
//
// The properties the plugins need arrive from the command line of this build:
// Gradle propagates command-line project properties to every included build of
// the composite, which is how `sdkRepo` and `sdkVersion` reach the plugins
// without any of them having to know where they were invoked from.
rootProject.name = "example-plugins"

includeBuild("../example-uppercase-plugin")
includeBuild("../example-directive-plugin")
includeBuild("../example-block-plugin")
includeBuild("../example-saga-plugin")
