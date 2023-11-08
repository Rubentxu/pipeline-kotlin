package dev.rubentxu.pipeline.backend


// credentials:
//  - name: gitlab
//    id: gitlab
//    type: username_password
//    user: e_rcabre
//    pass: password.
//  - name: gitlab_token
//    id: gitlab_token
//    type: secret_text
//    text: e_rcabre-token
//
//scmConfig:
//  userRemoteConfigs:
//    - name: gradle-simple
//      url: https://github.com/jitpack/gradle-simple
//      refspec: ''
//      credentialsId: 'gitlab'
//  branches:
//    - name: master
//  extensions:
//    sparseCheckoutPaths: []
//    localBranch: ''
//    relativeTargetDirectory: ''
//    cleanCheckout: true
//
//sharedLibrary:
//  name: commons
//  version: master
//  source:
//    local: src/test/resources/scripts
//
//
//environment:
//  JOB_NAME: Ejemplo-pipeline
//  MENSAJE: Hola Mundo

data class Config(
    val credentials: List<Credential> = emptyList(),
    val scmConfig: ScmConfig = ScmConfig(),
    val sharedLibrary: SharedLibrary,
    val environment: Map<String, String>,
)

data class Credential(
    val name: String,
    val id: String,
    val type: String,
    val user: String? = null,
    val pass: String? = null,
    val text: String? = null
)

data class ScmConfig(
    val userRemoteConfigs: List<UserRemoteConfig> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val extensions: Extensions = Extensions()
)

data class UserRemoteConfig(
    val name: String,
    val url: String,
    val refspec: String,
    val credentialsId: String
)

data class Branch(
    val name: String
)

data class Extensions(
    val sparseCheckoutPaths: List<String> = emptyList(),
    val localBranch: String = "master",
    val relativeTargetDirectory: String = ".",
    val cleanCheckout: Boolean = true
)

data class SharedLibrary(
    val name: String,
    val version: String,
    val source: Source
)

data class Source(
    val local: String
)

