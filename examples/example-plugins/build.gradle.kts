// The aggregator of this composite. It has no sources of its own: its only job
// is to ask the four INCLUDED builds for their `jar` task, and that is what
// makes them build concurrently instead of one after another.
//
// The dependency is expressed through `gradle.includedBuilds` rather than by
// NAME, so the aggregator cannot build a different set of plugins than the one
// this directory includes: it builds exactly what the settings file includes,
// and it fails closed when that is empty.
tasks.register("buildExamplePlugins") {
    group = "build"
    description = "Builds the four external example plugins concurrently, in one invocation."

    val includedBuilds = gradle.includedBuilds.toList()
    if (includedBuilds.isEmpty()) {
        throw GradleException(
            "buildExamplePlugins: this composite includes no build. Its settings file must " +
                "include the four external example plugins, or this task would report a " +
                "success that built nothing.",
        )
    }
    includedBuilds.forEach { included ->
        dependsOn(included.task(":jar"))
    }
}
