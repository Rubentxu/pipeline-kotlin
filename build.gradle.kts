
val ktorVersion: String by project
val kotlinVersion: String by project
val logbackVersion: String by project
val kotlinCoroutinesVersion:  String by project
val kotestVersion:  String by project
val appVersion:  String by project


allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }

}
