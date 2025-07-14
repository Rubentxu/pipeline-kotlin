plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":pipeline-steps-system:compiler-plugin"))
}
