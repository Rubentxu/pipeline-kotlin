//@ expect-exit 2
//@ expect-stderr Plugin admission refused for
//@ expect-stderr ArtifactNotMeasurable
//@ plugin-jar missing

// The subject is a plugin artifact the runtime cannot read.
//
// The launcher points the run at a plugin JAR that is not there. That is an ordinary operator
// mistake: a path typed wrong, or a JAR that was moved since the command line was written. What
// matters is what the product does with it, and the answer has to be a refusal that names the
// artifact. An artifact whose bytes cannot be read is an artifact whose identity cannot be
// established, and one that cannot be established is not one that may be loaded.
//
// The exit code is the product's code for a refused plugin, and the two messages are what an
// operator actually reads. A run that merely FAILED would leave open whether the plugin was
// skipped or loaded, and a skip is the failure this scenario exists to rule out: the pipeline
// below would run perfectly well without the plugin, so a green run here would be
// indistinguishable from a run that quietly ran with one plugin fewer.
pipeline {
    stages {
        stage("Never runs") {
            sh("echo this stage must never run")
        }
    }
}
