#!/usr/bin/env python3
"""The table of equivalences with Jenkins names nothing the tree does not hold.

THE GUARANTEE
  The catalogue publishes, for every entry, the step of Jenkins it corresponds
  to and the divergence it has with it. This guard says three things about
  that table, and it says them about the TREE and not about a document:

    1. FORMA: every equivalent the tree declares is a step that Jenkins
       publishes, checked against the DICTIONARY OF JENKINS STEPS that this
       guard carries with its source.
    2. COBERTURA: every step of the tree has an entry, and every entry
       declares a fact. A step of the tree is a CONTRACT: the contract names
       a key, and the descriptor it carries describes THAT key. An identity
       of the body machinery is not a contract, and so it is not a step.
    3. EL README: the READMEs publish what the tree declares. They may
       mention a step of Jenkins in an example --that is what the table of
       equivalences is for-- but the sample of the index they carry is
       checked row by row, and the card of one entry is checked field by
       field.

WHY THREE LEGS AND NOT ONE
  The table is DERIVED from the tree: every entry reads the facts the
  descriptor of its step declares. A table that is derived cannot promise a
  step the tree does not have -- and it can, in three ways that the derivation
  does not see:

    - an equivalent that names something Jenkins does not publish, so the table
      points a reader at a step that does not exist;
    - a row that declares no fact at all, so the table says nothing about an
      entry and the reader cannot tell it from an oversight;
    - a step whose contract carries no descriptor, or carries one that
      describes another step, so the catalogue publishes nothing for a step
      the tree offers;
    - a README that carries a table of its own, which is a second authority
      for a fact the code already owns and that drifts the first time a step
      is added.

  Each leg is a different failure, and a guard that checked one of them would
  let the other two through.

THE DICTIONARY OF JENKINS STEPS
  The dictionary is the OTHER product's, and it is not a second authority of
  this one: it says what Jenkins publishes, and it says nothing about what
  this product may map. It is the index of the "Pipeline Steps" reference of
  Jenkins, which lists every step a pipeline can run, grouped by the plugin
  page that documents each one:

    https://www.jenkins.io/doc/pipeline/steps/

  It was read on 2026-11-12. The plugin of a name is the page that documents it,
  so any name of the dictionary can be checked against the page this guard
  names. A name Jenkins publishes and the dictionary does not carry is a name
  the dictionary has to learn: the guard says so, and it says which name.

WHAT IT READS, AND WHAT IT DOES NOT
  The guard reads the PRODUCTION sources of the tree --the files under a
  `src/main/kotlin` of the modules of the product and of the plugins of the
  examples-- and it reads what the catalogue is built from: the contracts of
  the steps, the rows the domain declares without a handler, and the
  definitions of the directives. It does NOT start the product, and it does
  NOT read the tests: a test may name a step the product does not have, and
  that is the business of the test.

  A row whose key the guard cannot read is a FINDING and not a silence: a
  step the guard cannot name is a step whose entry nobody can check. The
  facts a row COMPUTES are read from the table of the file that holds them,
  because a fact the guard cannot read is a fact the guard cannot check.

THE CONTROL OF THE GUARD
  A guard that cannot fail is not a guard. Every run of this guard also runs
  its control: it writes a small tree whose rows carry an invented
  equivalent, a row with no fact at all and a README that names a step nobody
  has, and it asks itself whether it finds the three. The control is part of
  the verdict: a guard whose control passes nothing is a guard that would let
  anything through.

Exit 0 when the table is honest and the control bites, 1 otherwise.

Usage: check-jenkins-surface.py [--repo ROOT]
"""

from __future__ import annotations

import argparse
import bisect
import os
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

#: The index of the steps a pipeline can run, as Jenkins publishes it.
JENKINS_STEPS_SOURCE = "https://www.jenkins.io/doc/pipeline/steps/"

#: The day this dictionary was read from the source above.
JENKINS_STEPS_READ_ON = "2026-11-12"

#: Every step Jenkins publishes, grouped by the plugin page that documents it.
#: The page of a name is the key it is filed under, so any name of the
#: dictionary can be checked against the page this guard names.
JENKINS_STEPS_BY_PLUGIN = {
    "42crunch-security-audit": "audit",
    "BlazeMeterJenkinsPlugin": "blazeMeterTest",
    "Office-365-Connector": "office365ConnectorSend",
    "Parameterized-Remote-Trigger": "triggerRemoteJob",
    "Surround-SCM-plugin": "sscm",
    "TestComplete": "testcompletetest",
    "abap-ci": "abapCi",
    "abend-aid": "abendAid",
    "acunetix-360-scan": "ACXScanBuilder",
    "add-changes-to-build-changelog": "addchangestobuildchangelog",
    "adobe-cloud-manager": "acmAdvancePipeline acmPipelineEnd acmPipelineStepState acmPollPipeline acmRepoSync acmStartPipeline",
    "advanced-installer-msi-builder": "advinstBuilder",
    "ai-agent": "aiAgent",
    "aio-tests": "aioImport",
    "akeyless": "withAkeyless",
    "alauda-devops-pipeline": "base64Decode",
    "alauda-pipeline": "AlaudaDeleteBuild alaudaDeployComponent alaudaDeployService alaudaNotify alaudaRetrieveComponent alaudaRetrieveIntegration alaudaRetrieveService alaudaSendNotification alaudaStartBuild",
    "alibabacloud-ecs": "EcsTemplate alibabaEcs",
    "alibabacloud-edas": "edasClient",
    "alibabacloud-pkg-deployment": "oosExecuteNotify oosStatusQuery ossUploadAndOosExec",
    "aliyun-oss-uploader": "aliyunOSSUpload",
    "allure-jenkins-plugin": "allure",
    "amazon-ecs": "ecsTaskTemplate",
    "amazon-inspector-image-scanner": "amazonInspector",
    "anchore-container-scanner": "anchore",
    "android-emulator": "adb androidEmulator avdmanager emulator sdkmanager",
    "android-signing": "signAndroidApks",
    "anka-build": "ankaGetSaveImageResult createDynamicAnkaNode",
    "ansible": "ansibleAdhoc ansiblePlaybook ansibleVault",
    "ansible-tower": "ansibleTower ansibleTowerProjectRevision ansibleTowerProjectSync",
    "ansicolor": "ansiColor",
    "ant": "withAnt",
    "apimap": "publishAPI validateAPI",
    "appcenter": "appCenter",
    "appcircle-enterprise-store": "appcircleEnterpriseAppStore",
    "appcircle-publish": "appcirclePublish",
    "appcircle-testing-distribution": "appcircleTestingDistribution",
    "appdome-build-2secure": "AppdomeBuilder",
    "appdome-validate-2secure": "AppdomeValidator",
    "appknox-scanner": "appKnoxScanner",
    "applatix": "applatix",
    "applitools-eyes": "Applitools",
    "appray": "appray",
    "appscan": "appscan appscanenterprise",
    "aqua-microscanner": "aquaMicroscanner",
    "aqua-security-scanner": "aqua",
    "aqua-serverless": "aquaServerlessScanner",
    "arachni-scanner": "arachniScanner",
    "arestocats": "arestocats",
    "aribot": "aribot",
    "armorcode-release-gate": "armorcodeReleaseGate",
    "artifact-promotion": "artifactPromotion",
    "artifactory": "ArtifactoryGradleBuild MavenDescriptorStep addInteractivePromotion artifactoryBuildTrigger artifactoryDistributeBuild artifactoryDownload artifactoryEditProps artifactoryGoPublish artifactoryGoRun artifactoryMavenBuild artifactoryNpmCi artifactoryNpmInstall artifactoryNpmPublish artifactoryNugetRun artifactoryPipRun artifactoryPromoteBuild artifactoryUpload buildAppend collectEnv collectIssues conanAddRemote conanAddUser createDockerBuildStep createReleaseBundle deleteReleaseBundle deployArtifacts distributeReleaseBundle dockerPullStep dockerPushStep dsCreateReleaseBundle dsDeleteReleaseBundle dsDistributeReleaseBundle dsSignReleaseBundle dsUpdateReleaseBundle getArtifactoryServer getJFrogPlatformInstance initConanClient jfPipelines jfrogInstance newArtifactoryServer newBuildInfo newGoBuild newGradleBuild newJFrogPlatformInstance newMavenBuild newNpmBuild newNugetBuild newPipBuild publishBuildInfo rtAddInteractivePromotion rtBuildAppend rtBuildInfo rtBuildTrigger rtCollectIssues rtConanClient rtConanRemote rtConanRun rtCreateDockerBuild rtDeleteProps rtDockerPull rtDockerPush rtDotnetResolver rtDotnetRun rtDownload rtGoDeployer rtGoPublish rtGoResolver rtGoRun rtGradleDeployer rtGradleResolver rtGradleRun rtMavenDeployer rtMavenResolver rtMavenRun rtNpmCi rtNpmDeployer rtNpmInstall rtNpmPublish rtNpmResolver rtNugetResolver rtNugetRun rtPipInstall rtPipResolver rtPromote rtPublishBuildInfo rtServer rtSetProps rtUpload runConanCommand signReleaseBundle updateReleaseBundle xrayScan xrayScanBuild",
    "artifactz": "artifactVersion publishArtifact pushArtifact retrieveArtifacts",
    "assertthat-bdd-jira": "assertthatBddFeatures assertthatBddReport",
    "asyntai-knowledge-sync": "asyntaiKnowledgeSync",
    "atlassian-bitbucket-server-integration": "bbs_checkout bbs_deploy",
    "atlassian-jira-software-cloud": "checkGatingStatus jiraSendBuildInfo jiraSendDeploymentInfo",
    "autify": "autifyMobile autifyMobileUpload autifyWeb",
    "autoaction-step": "chlAtuoAction",
    "autocancel": "autocancelBranchBuildsOnPullRequestBuilds autocancelConsecutiveBuilds",
    "autograding": "autoGrade",
    "awarely-scan": "awarelyScan",
    "aws-beanstalk-releaser": "awsebReleaser",
    "aws-codebuild": "awsCodeBuild",
    "aws-device-farm": "devicefarm",
    "aws-lambda": "deployLambda eventSourceLambda invokeLambda publishLambda",
    "aws-parameter-store": "withAWSParameterStore",
    "aws-sam": "samDeploy",
    "azure-acs": "acsDeploy",
    "azure-app-service": "azureWebAppPublish azureWebAppSwapSlots",
    "azure-container-registry-tasks": "acrQuickTask",
    "azure-cosmosdb": "azureCosmosDBCreateDocument",
    "azure-function": "azureFunctionAppPublish",
    "azure-keyvault": "azureKeyVault withAzureKeyvault",
    "azure-vmss": "azureVMSSUpdate azureVMSSUpdateInstances",
    "backlog": "backlogPullRequest",
    "badge": "addBadge addErrorBadge addInfoBadge addSummary addWarningBadge removeBadges removeSummaries",
    "benchmark-evaluator": "benchmark",
    "bevigil-ci": "greet",
    "bitbucket-build-status-notifier": "bitbucketStatusNotify",
    "blackduck-coverity-on-polaris": "polaris polarisIssueCheck",
    "blackduck-detect": "blackduck_detect",
    "blackduck-security-scan": "security_scan",
    "breachlock-dast": "DASTScan",
    "browserstack-integration": "browserStackReportAut browserStackReportPublisher browserstack browserstackAppUploader",
    "build-name-setter": "buildDescription buildName",
    "build-token-trigger": "buildTokenTrigger",
    "build-user-vars-plugin": "withBuildUser",
    "buildkite": "buildkite",
    "buildstash": "buildstash",
    "byteguard-build-actions": "byteguardGreet",
    "ca-service-virtualization": "svCreateAndDeployVirtualService svDeployTest svDeployVirtualService svPublishTestReport svStartVirtualService svStopVirtualService svUndeployVirtualService",
    "cachet-gating": "cachetgatingmetrics",
    "carbonetes-serverless-container-scanning-and-policy-compliance": "carbonetes",
    "carl": "carl",
    "cerberus-testing": "executeCerberusCampaign",
    "change-assembly-version-plugin": "changeAsmVer",
    "chatter-notifier": "chatterPost",
    "checkmarx-ast-scanner": "checkmarxASTScanner",
    "checks-api": "publishChecks withChecks",
    "chef-cookbook-pipeline": "chef_cookbook_cookstyle chef_cookbook_foodcritic chef_cookbook_functional chef_cookbook_unit",
    "chinese-workday": "chineseWorkday chineseWorkdaySupportedYears isChineseHoliday isChineseWorkday",
    "chucknorris": "chuckNorris",
    "cisco-spark-notifier": "sparkSend",
    "cloudaeye": "sendNotificationsToCloudAEye",
    "cloudbees-feature-management": "featureManagementConfig",
    "cloudfoundry": "pushToCloudFoundry",
    "cloudhub-deployer": "cloudhubDeployer",
    "cloudify": "cfyAnsible cfyAzureArm cfyCloudFormation cfyKubernetes cfyOutputsToInputs cfyTerraform createCloudifyEnv deleteCloudifyBlueprint deleteCloudifyEnv executeCloudifyWorkflow uploadCloudifyBlueprint",
    "cloudshare-docker": "cloudshareDockerMachine",
    "cloudshell-sandbox": "startSandbox stopSandbox withSandbox",
    "clover": "clover",
    "cmakebuilder": "cmake cmakeBuild cpack ctest",
    "cobertura": "cobertura",
    "code-coverage-api": "publishCoverage",
    "codebeamer-xunit-importer": "xUnitImporter",
    "codebeamer-xunit-uploader": "xUnitUploader",
    "codefresh": "codefreshLaunch codefreshRun",
    "codeql": "withCodeQL",
    "codescene": "codescene",
    "codesonar": "codesonar",
    "codethreat-scanner": "CodeThreatScan",
    "collabnet": "publishTeamForge",
    "compuware-ispw-operations": "gitToIspwIntegration ispwOperation ispwRegisterWebhook ispwWaitForWebhook",
    "compuware-strobe-measurement": "strobeMeasurement",
    "compuware-topaz-for-enterprise-data": "ted",
    "compuware-topaz-for-total-test": "totaltest totaltestUT",
    "compuware-topaz-utilities": "topazSubmitFreeFormJcl topazSubmitJclMembers",
    "compuware-zadviser-api": "zAdviserDownload zAdviserUpload",
    "concurrent-step": "acquireSemaphore awaitBarrier awaitCondition awaitLatch countDownLatch createBarrier createCondition createLatch createSemaphore releaseSemaphore signalAll",
    "config-file-provider": "configFileProvider",
    "config-splice": "configSubstitution",
    "consul": "Consul",
    "content-replace": "contentReplace",
    "continuum": "ctmInitiatePipeline ctmPostPiData ctmSetPiData",
    "contrast-continuous-application-security": "contrastAgent contrastVerification",
    "conventional-commits": "currentVersion nextVersion",
    "copyartifact": "copyArtifacts",
    "core": "archiveArtifacts fingerprint",
    "cortex-cloud": "cortexScanImage",
    "cortex-metrics": "publishCortexMetrics",
    "coverage": "recordCoverage",
    "coverity": "coverityResults withCoverityEnv",
    "cppcheck": "publishCppcheck",
    "createos-sandbox": "createosDownload createosSandbox createosSh createosUpload",
    "credentials-binding": "withCredentials",
    "crowdstrike-security": "crowdStrikeSecurity",
    "crx-content-package-deployer": "crxBuild crxDeploy crxDownload crxReplicate crxValidate",
    "ctrf-json": "publishCtrfResults",
    "cucumber-living-documentation": "livingDocs",
    "cucumber-slack-notifier": "cucumberSlackSend",
    "cucumber-testresult-plugin": "cucumber",
    "custom-build-properties": "getCustomBuildProperty setCustomBuildProperty setJUnitCounts waitForCustomBuildProperties",
    "database": "getDatabaseConnection sql",
    "datadog": "datadog",
    "datatheorem-mobile-app-security": "sendBuildToDataTheorem",
    "debian-pbuilder": "debianPbuilder",
    "deepcrawl-test": "runAutomationHubBuild",
    "deepsecurity-smartcheck": "smartcheckScan",
    "defectdojo": "defectDojoPublisher",
    "defensics": "defensics",
    "delivery-pipeline-plugin": "task",
    "delphix": "deleteVDB provisionVDBFromBookmark provisionVDBFromSnapshot",
    "dependency-check-jenkins-plugin": "dependencyCheck dependencyCheckPublisher",
    "dependency-track": "dependencyTrackPublisher",
    "deploy": "deploy",
    "deploy-dashboard": "addDeployToDashboard buildAddUrl",
    "deploy-integrationserver": "deployintegrationserver",
    "deployit-plugin": "xldCreatePackage xldDeploy xldPublishPackage",
    "deployment-notification": "awaitDeployment",
    "devops-portal": "reportArtifactRelease reportBuild reportDeployOperation reportJMeterPerformanceTest reportMavenDependenciesAnalysis reportPerformanceTest reportQualityAudit reportSonarQubeAudit reportSurefireTest reportUnitTest",
    "digicert-software-trust-code-sign": "SoftwareTrustManagerSetup",
    "dimensionsscm": "dimensionsscm",
    "dingding-json-pusher": "dingding",
    "dingding-notifications": "dingtalk",
    "discord-notifier": "discordSend",
    "docker-plugin": "dockerNode",
    "docker-workflow": "dockerFingerprintFrom dockerFingerprintRun withDockerContainer withDockerRegistry withDockerServer",
    "dogu-integration": "doguRunRoutine doguUploadApplication",
    "doktor": "doktor",
    "dotcommonitor-loadview": "dotcomMonitor",
    "dotcoverrunner": "dotcover",
    "dotnet-sdk": "dotnetBuild dotnetClean dotnetListPackage dotnetNuGetDelete dotnetNuGetLocals dotnetNuGetPush dotnetPack dotnetPublish dotnetRestore dotnetTest dotnetToolRestore withDotNet",
    "downstream-build-cache": "downstreamBuilds",
    "dynatrace-dashboard": "appMonBuildEnvironment appMonPublishTestResults appMonRegisterTestRun",
    "ec2": "ec2",
    "ecu-test-execution": "ttCheckPackage ttGenerateReports ttLoadConfig ttProvideGeneratedReports ttProvideLogs ttProvideReports ttProvideUnitReports ttRunPackage ttRunProject ttRunTestFolder ttStartTool ttStopTool ttUploadReports",
    "ecutest": "checkETLicense downstreamPublisher exportPackages exportProjects generateCache getATXServer getETInstallation importPackages importProjects isConfigStarted newATXServer newETInstallation publishATX publishETLogs publishGenerators publishTRF publishTraceAnalysis startET startTS stopET stopTS testFolder testPackage testProject",
    "eggplant-runner": "eggplantRunner",
    "eiffel-broadcaster": "buildWithEiffel createPackageURL publishEiffelArtifacts sendEiffelEvent",
    "elastest": "elastest",
    "electricflow": "cloudBeesFlowAssociateBuildToRelease cloudBeesFlowCallRestApi cloudBeesFlowCreateAndDeployAppFromJenkinsPackage cloudBeesFlowDeployApplication cloudBeesFlowPublishArtifact cloudBeesFlowRunPipeline cloudBeesFlowRunProcedure cloudBeesFlowTriggerRelease",
    "email-ext": "emailext emailextrecipients",
    "embeddable-build-status": "addEmbeddableBadgeConfiguration",
    "embotics-vcommander": "vCommander",
    "endtest": "endtestRun",
    "environment-dashboard": "environmentDashboard",
    "environment-variables-status-sync": "notify",
    "exam": "examCleanTarget examRun_Groovy examTCG examTest_ExecutionFile examTest_Model",
    "explain-error": "explainError",
    "external-workspace-manager": "exws exwsAllocate",
    "fedora-module-build-system": "queryModuleBuildRequest submitModuleBuildRequest",
    "figlet-buildstep": "figlet",
    "file-operations": "fileOperations",
    "file-parameters": "withFileParameter",
    "finite-state-analysis": "finiteStateAnalyzeBinary finiteStateImportSbom finiteStateImportThirdParty",
    "flyway-runner": "flywayrunner",
    "folder-properties": "withFolderProperties",
    "forensics-api": "discoverReferenceBuild mineRepository",
    "forgeai-pipeline-intelligence": "forgeAI forgeAIScan",
    "forticwp-cicd": "fortiCWPScanner",
    "fortify": "fortifyClean fortifyRemoteAnalysis fortifyRemoteArguments fortifyRemoteScan fortifyScan fortifyTranslate fortifyUpdate fortifyUpload",
    "fortify-on-demand-uploader": "fodPollResults fodStaticAssessment",
    "frugal-testing": "frugalTesting",
    "gamekins": "gamekins",
    "gating-core": "requireResources",
    "gatling": "gatlingArchive",
    "gatling-check": "gatlingCheck",
    "gcr-scanner": "gcrImageVulnerabilityScanner",
    "genexus": "gxserver",
    "gerrit-code-review": "gerritCheck gerritComment gerritReview",
    "gerrit-trigger": "setGerritReview",
    "ghost-inspector": "ghostInspector",
    "git": "git",
    "git-automerger": "gitAutomerger",
    "git-bisect": "gitbisect",
    "git-changelog": "getHighestSemanticVersion getNextSemanticVersion gitChangelog",
    "git-collect": "collectGit",
    "git-forensics": "discoverGitReferenceBuild gitDiffStat",
    "git-push": "gitPush",
    "gitea": "publishGiteaAssets",
    "gitee": "acceptGiteeMR addGiteeMRComment",
    "github-coverage-reporter": "publishCoverageGithub",
    "github-pullrequest": "gitHubPRStatus githubPRAddLabels githubPRClosePublisher githubPRComment githubPRRemoveLabels githubPRStatusPublisher setGitHubPullRequestStatus",
    "github-release": "createGitHubRelease listGitHubReleases uploadGithubReleaseAsset",
    "gitlab-plugin": "GitLabMergeRequestLabelExists acceptGitLabMR addGitLabMRComment gitlabBuilds gitlabCommitStatus updateGitlabCommitStatus",
    "global-pipeline-parameters": "gpRead gpWrite",
    "global-yaml-properties": "getGlobalYAMLCategories getGlobalYAMLConfigNames getGlobalYAMLConfigNamesByCategory getGlobalYAMLProperties getLocalYAMLProperties",
    "google-chat-notification": "googlechatnotification",
    "google-cloudbuild": "googleCloudBuild",
    "google-kubernetes-engine": "kubernetesEngineDeploy",
    "google-play-android-publisher": "androidApkMove androidApkUpload",
    "google-storage-plugin": "googleStorageBucketLifecycle googleStorageBuildLogUpload googleStorageDownload googleStorageUpload",
    "gprbuild": "gprbuild",
    "gradle": "findBuildScans withGradle",
    "groovy": "withGroovy",
    "grypescanner": "grypeScan",
    "habitat": "habitat",
    "harbor": "waitForHarborWebHook",
    "harness": "harnessCliEnv hc",
    "hashicorp-vault-pipeline": "vault",
    "hashicorp-vault-plugin": "withVault",
    "helix-alm-test-management": "halm_report",
    "hiddenlayer-model-scanner": "hlScanModel",
    "hipchat": "hipchatSend",
    "horreum": "horreumExpect horreumUpload",
    "hp-application-automation-tools-plugin": "addALMOctaneSonarQubeListener collectBranchesToAlmOctane collectPullRequestsToAlmOctane commonResultUploadBuilder convertTestsToRun executeTestsFromAlmOctane healthAnalyzer loadRunnerTest pcBuild publishCodeCoverage publishGherkinResults publishMicroFocusTestResults runFromAlmBuilder runFromCodelessBuilder runFromFSBuilder runLoadRunnerScript sseBuild sseBuildAndPublish svChangeModeStep svDeployStep svExportStep svUndeployStep uftScenarioLoad uploadResultToALM",
    "hp-quality-center": "qc",
    "htmlpublisher": "publishHTML",
    "http_request": "httpRequest",
    "hubot-steps": "hubotApprove hubotSend",
    "hugo": "hugo hugoGitPublish hugoGitSubmodulePublsh",
    "ibm-cloud-devops": "evaluateGate notifyOTC publishBuildRecord publishDeployRecord publishSQResults publishTestResult sendDeployableMessage",
    "ibmi-steps": "ibmiCommand ibmiGetIFS ibmiGetSAVF ibmiGetSPLF ibmiPutIFS ibmiPutSAVF ibmiRunSQL ibmiShellExec ibmiWaitJob onIBMi",
    "icq-notification": "icqMessage",
    "in-toto": "in_toto_wrap",
    "indusface-was": "indusfaceWasScan",
    "inedo-buildmaster": "buildMasterCreateBuild buildMasterDeployBuildToStage buildMasterWithApplicationRelease",
    "inedo-proget": "downloadProgetPackage uploadProgetPackage",
    "infisical": "withInfisical",
    "influxdb": "influxDbPublisher",
    "influxdb-query": "influxDbQuery",
    "insightappsec": "insightAppSec",
    "instana": "releaseMarker",
    "integrity-plugin": "siaddprojectlabel sicheckpoint sici",
    "interactive-ci": "askInteractive interactiveOutput interactiveView",
    "ircbot": "ircNotify",
    "jabber": "jabberNotify",
    "jacked": "jacked",
    "jacoco": "jacoco",
    "javadoc": "javadoc",
    "jclouds-jenkins": "jcloudsOneOffAgent jcloudsTakeOffline withJclouds",
    "jetbrains-space": "callSpaceApi postBuildStatusToSpace postReviewTimelineMessageToSpace",
    "jfrog": "jf jfrogCliEnv",
    "jira": "jiraComment jiraCommentIssues jiraCreateReleaseNotes jiraCreateVersion jiraExecuteWorkflow jiraIssueSelector jiraMarkVersionReleased jiraSearch jiraUpdateIssueField",
    "jira-integration": "jiraDeploymentInfo",
    "jira-steps": "jiraAddComment jiraAddWatcher jiraAssignIssue jiraAssignableUserSearch jiraDeleteAttachment jiraDeleteIssueLink jiraDeleteIssueRemoteLink jiraDeleteIssueRemoteLinks jiraDownloadAttachment jiraEditComment jiraEditComponent jiraEditIssue jiraEditVersion jiraGetAttachmentInfo jiraGetComment jiraGetComments jiraGetComponent jiraGetComponentIssueCount jiraGetFields jiraGetIssue jiraGetIssueLink jiraGetIssueLinkTypes jiraGetIssueRemoteLink jiraGetIssueRemoteLinks jiraGetIssueTransitions jiraGetIssueWatches jiraGetProject jiraGetProjectComponents jiraGetProjectStatuses jiraGetProjectVersions jiraGetProjects jiraGetServerInfo jiraGetVersion jiraJqlSearch jiraLinkIssues jiraNewComponent jiraNewIssue jiraNewIssueRemoteLink jiraNewIssues jiraNewVersion jiraNotifyIssue jiraTransitionIssue jiraUploadAttachment jiraUserSearch",
    "jmh-report": "jmhReport",
    "jms-messaging": "sendCIMessage waitForCIMessage",
    "job-dsl": "jobDsl",
    "jobcacher": "cache jobcacher",
    "jsm-alert": "JSM",
    "junit": "junit withOpenTestReporting",
    "junit-realtime-test-reporter": "realtimeJUnit",
    "kafkalogs": "withKafkaLog",
    "katalon": "executeKatalon executeKatalonTestOps",
    "keeper-secrets-manager": "withKsm",
    "kiuwanJenkinsPlugin": "kiuwan",
    "klocwork": "klocworkBuildSpecGeneration klocworkFailureCondition klocworkIncremental klocworkIntegrationStep1 klocworkIntegrationStep2 klocworkIssueSync klocworkWrapper",
    "kobiton-integration": "appUploaderBuilder credentialsBuildWrapper",
    "kryptowire": "kwSubmit",
    "kubernetes": "container containerLog kubeconfig podTemplate",
    "kubernetes-cli": "withKubeConfig withKubeCredentials",
    "kubernetes-ephemeral-container": "ephemeralContainer withEphemeralContainer",
    "kubernetes-pipeline-devops-steps": "approveReceivedEvent approveRequestedEvent createEvent kubernetesApply",
    "labelled-steps": "labelledShell",
    "lacework-security-scanner": "lacework",
    "lambdatest-automation": "lambdaTestReportPublisher",
    "last-changes": "getLastChangesPublisher lastChanges publishLastChanges",
    "leanix-microservice-intelligence": "leanIXMicroserviceIntelligence",
    "lighthouse-report": "lighthouseReport",
    "loadcomplete": "loadcompletetest",
    "loadfocus-jmeter-load-testing-integration": "loadfocusJMeterTest",
    "loadfocus-loadtest": "loadfocusLoadTest",
    "loadmance": "loadmance",
    "loadrunner-cloud": "lrcGenTrendingReport lrcRunTest",
    "lockable-resources": "lock updateLock",
    "log-file-filter": "logFileFilter",
    "log-flow-visualizer": "logFlowVisualizer",
    "log-parser": "logParser",
    "logstash": "logstash logstashSend",
    "mabl-integration": "mabl",
    "machine-learning": "ipythonBuilder",
    "marathon": "marathon",
    "markdown-params": "markdownParams",
    "mask-passwords": "maskPasswords",
    "matlab": "runMATLABBuild runMATLABCommand runMATLABTests",
    "matrix-communication": "matrixSendMessage",
    "mattermost": "mattermostSend",
    "maven-invoker-plugin": "maven_invoker",
    "maven-snapshot-check": "mavenSnapshotCheck",
    "meliora-testlab": "melioraTestlab",
    "memory-map": "memoryMap",
    "mentor-questa-vrm": "questavrm",
    "mergebase-sca": "mergebaseScan",
    "metrics-aggregation": "metrics",
    "micro-focus-performance-center-integration": "pcGitBuild pcRunBuild",
    "minio": "minio minioDelete minioDownload",
    "misc-info-tools": "findJobs getAllLabelsForAllNodes getCurrentBuildHost getLastSuccessfulBuildNumber relatedJobChecks",
    "misra-compliance-report-generator": "misraReport",
    "mock-load-builder": "withMockLoad",
    "mq-notifier": "publishMQMessage",
    "mqtt-notification-plugin": "mqttNotification",
    "mstest": "mstest",
    "neoload-jenkins-plugin": "neoloadRefreshTrends neoloadRun",
    "nested-data-reporting": "publishReport",
    "netsparker-cloud-scan": "NCScanBuilder",
    "neuvector-vulnerability-scanner": "neuvector",
    "nexus-artifact-uploader": "nexusArtifactUploader",
    "nirmata": "nirmata",
    "nodejs": "nodejs",
    "nodepool-agents": "nodePoolHold",
    "notifer": "notifer",
    "notification": "notifyEndpoints",
    "notify-events": "notifyEvents",
    "nowsecure-auto-security-test": "apiKey",
    "nowsecure-ci-assessments": "nowsecureAssessment",
    "npm-yarn-wrapper-steps": "npm withNPMWrapper yarn",
    "nunit": "nunit",
    "nvm-wrapper": "nvm",
    "octane-status-tracker": "octaneCronProgressEmail octaneEmailReport octaneSuiteGate octaneSuiteGateBuilder",
    "octoperf": "octoPerfTest",
    "octopusdeploy": "octopusCreateRelease octopusDeployRelease octopusPack octopusPushBuildInformation octopusPushPackage",
    "onepassword-secrets": "withSecrets",
    "onesky": "OneSky",
    "ontrack": "ontrackBranchName ontrackBranchSetup ontrackBuild ontrackGraphQL ontrackProjectSetup ontrackPromote ontrackScript ontrackValidate",
    "openshift-pipeline": "openshiftBuild openshiftCreateResource openshiftDeleteResourceByJsonYaml openshiftDeleteResourceByKey openshiftDeleteResourceByLabels openshiftDeploy openshiftExec openshiftImageStream openshiftScale openshiftTag openshiftVerifyBuild openshiftVerifyDeployment openshiftVerifyService",
    "openstack-cloud": "openstackMachine",
    "opentelemetry": "setSpanAttributes withNewSpan withSpanAttribute withSpanAttributes",
    "opentelemetry-agent-metrics": "onMonit",
    "opsgenie": "opsgenie",
    "opslevel": "opsLevelNotify",
    "oracle-cloud-infrastructure-devops": "OCIDeployment OCIUploadArtifact",
    "osf-builder-suite-for-sfcc-data-import": "osfBuilderSuiteForSFCCDataImport",
    "osf-builder-suite-for-sfcc-deploy": "osfBuilderSuiteForSFCCDeploy",
    "osf-builder-suite-for-sfcc-run-job": "osfBuilderSuiteForSFCCRunJob",
    "osf-builder-suite-standalone-sonar-linter": "osfBuilderSuiteStandaloneSonarLinter",
    "ostorlab": "ostorlabScan",
    "overops-query": "OverOpsQuery",
    "oversecured": "oversecuredUpload",
    "p4": "cleanup p4 p4SwarmUpdate p4approve p4publish p4sync p4tag p4unshelve",
    "p4sa": "p4StaticAnalysis",
    "package-drone": "pdrone",
    "pagerduty": "pagerduty pagerdutyChangeEvent",
    "pangolin-testrail-connector": "pangolinRunReport pangolinTestRail",
    "parallel-test-executor": "splitTests",
    "parasoft-findings": "recordParasoftCoverage",
    "peass-ci": "cleanPerformanceMeasurement measure peassOverview",
    "performance": "bzt perfReport",
    "performance-signature-dynatrace": "activateDTConfiguration createDeploymentEvent createMemoryDump createThreadDump perfSigReports startSession stopSession",
    "performance-signature-dynatracesaas": "createDynatraceDeploymentEvent perfSigDynatraceReports recordDynatraceCustomSession recordDynatraceSession",
    "performance-signature-viewer": "pullPerfSigReports triggerInputStep",
    "perfpublisher": "perfpublisher",
    "piketec-tpt": "tptAgent tptExecute tptReport",
    "pipeline-aws": "awaitDeploymentCompletion awsIdentity cfInvalidate cfnCreateChangeSet cfnDelete cfnDeleteStackSet cfnDescribe cfnExecuteChangeSet cfnExports cfnUpdate cfnUpdateStackSet cfnValidate createDeployment deployAPI ebCreateApplication ebCreateApplicationVersion ebCreateConfigurationTemplate ebCreateEnvironment ebSwapEnvironmentCNAMEs ebWaitOnEnvironmentHealth ebWaitOnEnvironmentStatus ec2ShareAmi ecrDeleteImage ecrListImages ecrLogin ecrSetRepositoryPolicy elbDeregisterInstance elbIsInstanceDeregistered elbIsInstanceRegistered elbRegisterInstance lambdaVersionCleanup listAWSAccounts s3Copy s3Delete s3DoesObjectExist s3Download s3FindFiles s3PresignURL s3Upload setAccountAlias snsPublish updateIdP updateTrustPolicy withAWS",
    "pipeline-bamboo": "buildBamboo",
    "pipeline-build-step": "build waitForBuild",
    "pipeline-dependency-walker": "walk",
    "pipeline-deploymon": "notifyDeploymon",
    "pipeline-gcp": "computeFirewallRulesCreate computeFirewallRulesDelete computeFirewallRulesList withGCP",
    "pipeline-giphy-api": "giphyRandom giphySearch giphySearchRandomByKeyword giphyTranslate",
    "pipeline-githubnotify-step": "githubNotify",
    "pipeline-gitstatuswrapper": "gitStatusWrapper",
    "pipeline-graph-view": "hideFromView",
    "pipeline-groovy-lib": "library libraryResource",
    "pipeline-huaweicloud-plugin": "invokeFunction obsUpload withOBS",
    "pipeline-input-step": "input",
    "pipeline-keep-running-step": "keepRunning",
    "pipeline-keepenv-step": "keepEnv",
    "pipeline-maven": "withMaven",
    "pipeline-milestone-step": "milestone",
    "pipeline-model-definition": "envVarsForTool script validateDeclarativePipeline",
    "pipeline-npm": "withNPM",
    "pipeline-project-env": "withProjectEnv",
    "pipeline-stage-step": "stage",
    "pipeline-utility-steps": "compareVersions findFiles md5 nodesByLabel prependToFile readCSV readJSON readManifest readMavenPom readProperties readTOML readYaml sha1 sha256 tar tee touch untar unzip verifyMd5 verifySha1 verifySha256 writeCSV writeJSON writeMavenPom writeTOML writeYaml zip",
    "pitmutation": "pitmutation",
    "plasticscm-plugin": "cm mergebotCheckout",
    "plot": "plot",
    "portainer": "portainerHelm portainerManifest portainerStack portainerStackConfig portainerStackSecret",
    "portshift-scanner": "secureCNVulnerabilityScanner",
    "pragprog": "pragprog",
    "pretested-integration": "pretestedIntegrationPublisher",
    "prisma-cloud-iac-scan": "prismaIaC",
    "probely-security": "probelyScan",
    "propelo-job-reporter": "seiReportPhase",
    "provar-automation": "withProvarAutomation",
    "publish-over-cifs": "cifsPublisher",
    "publish-over-dropbox": "dropbox",
    "publish-over-ftp": "ftpPublisher",
    "publish-over-ssh": "sshPublisher",
    "pull-request-monitoring": "monitoring",
    "pureload": "publishPureLoad",
    "pushplus": "pushplus",
    "pyenv-pipeline": "pybat pysh withPythonEnv",
    "python-tool": "withPython",
    "qftest": "QFTest",
    "qiniu": "archiveArtifactsToQiniu",
    "qrebel": "qrebel",
    "qtest": "submitJUnitTestResultsToqTest",
    "quali-torque": "endTorqueSandboxEnvironment startTorqueSandboxEnvironment waitForTorqueSandboxEnvironment",
    "qualityclouds": "qualityCloudsScan",
    "qualys-cs": "getImageVulnsFromQualys",
    "qualys-iac-security": "qualysIaCScan",
    "qualys-pc": "qualysPolicyComplianceScanner",
    "qualys-vm": "qualysVulnerabilityAnalyzer",
    "qualys-was": "qualysWASScan",
    "quay-tag-parameter": "quayImage",
    "qy-wechat-notification": "qyWechatNotification",
    "r": "r",
    "rabbitmq-publisher": "rabbitMQPublisher",
    "radargun-reporting": "radargunreporting",
    "railflow-testrail": "railflow",
    "rancher": "rancher",
    "rancher-manager": "rancherHelm rancherManifest",
    "ranorex-integration": "ranorex",
    "rapid7-insightvm-container-assessment": "assessContainerImage",
    "rb": "notifyReviewBoard publishReview",
    "redeploy-rancher2-workload": "rancherRedeploy",
    "redhat-dependency-analytics": "rhdaAnalysis",
    "redmine-metrics-report": "redmineMetricsReport",
    "release": "release",
    "reliza-integration": "addRearmRelease addRelizaRelease submitPrData withRearm withReliza",
    "remote-env-file": "withRemoteEnvFiles",
    "remote-result-trigger": "pubResult readRemoteJobs readRemoteResult",
    "report-generic-chart-column": "genericChartPublisher",
    "repository-connector": "artifactDeployer artifactResolver",
    "reqtify": "reqtifyFunction reqtifyReport",
    "rich-text-publisher-plugin": "rtp",
    "robot": "robot",
    "rocketchatnotifier": "rocketSend",
    "role-strategy": "currentUserGlobalRoles currentUserItemRoles",
    "run-selector": "selectRun",
    "s3": "s3CopyArtifact",
    "saltstack": "salt",
    "sauce-ondemand": "sauce saucePublisher sauceconnect",
    "scm-httpclient": "scmHttpClient",
    "scmskip": "scmSkip",
    "sdelements": "sdelements",
    "secone-sca-sast-security-scanner": "sec1MobileScan sec1Security",
    "sedstart-runner": "sedStart",
    "sensedia-api-platform": "sensediaApiDeploy sensediaApiJson sensediaApiQA",
    "service-fabric": "azureServiceFabricPublish",
    "service-now": "serviceNow_attachFile serviceNow_attachZip serviceNow_createChange serviceNow_getCTask serviceNow_getChangeState serviceNow_updateChangeItem serviceNow_updateTask",
    "servicenow-cicd": "snActivatePlugin snApplyChanges snBatchInstall snBatchRollback snInstallApp snInstanceScan snPublishApp snRollbackApp snRollbackPlugin snRunTestSuite",
    "servicenow-devops": "snDevOpsArtifact snDevOpsChange snDevOpsConfig snDevOpsConfigExport snDevOpsConfigGetSnapshots snDevOpsConfigPublish snDevOpsConfigRegisterPipeline snDevOpsConfigUpload snDevOpsConfigValidate snDevOpsGetChangeNumber snDevOpsPackage snDevOpsSecurityResult snDevOpsStep snDevOpsUpdateChangeInfo",
    "signpath": "getSignedArtifact submitSigningRequest",
    "simplifyqa-pipeline-executor": "SQAPipelineExecutor simplifyQA",
    "skenai": "skenai",
    "slack": "slackSend slackUploadFile slackUserIdFromEmail slackUserIdsFromCommitters",
    "sloccount": "sloccountPublish",
    "slsa": "provenanceRecorder",
    "smart-retry": "smartRetry",
    "smilehubnotifier": "smilehubSend",
    "snowglobe": "snowglobe_apply snowglobe_clone snowglobe_destroy snowglobe_get_variables snowglobe_set_variables snowglobe_state",
    "snyk-security-scanner": "snykSecurity",
    "soapui-pro-functional-testing": "SoapUIPro",
    "sonar": "waitForQualityGate withSonarQubeEnv",
    "sonar-gerrit": "sonarToGerrit",
    "sonargraph-integration": "SonargraphReport",
    "split-admin": "split",
    "splunk-devops-extend": "sendSplunkConsoleLog sendSplunkFile",
    "spring-config": "springConfig springProfiles",
    "spring-initalzr": "springBoot",
    "ssh-agent": "sshagent",
    "ssh-steps": "sshCommand sshGet sshPut sshRemove sshScript",
    "stackrox-container-image-scanner": "stackrox",
    "stashNotifier": "notifyBitbucket",
    "stepcounter": "stepcounter",
    "stoplightio-report": "publishStoplight",
    "subversion": "svn",
    "sumologic-publisher": "SumoPipelineLogCollection SumoSDOEvent SumoUpload",
    "swarm-agents-cloud": "swarmAgent",
    "sweagle": "SWEAGLEExport SWEAGLESnapshot SWEAGLEUpload SWEAGLEValidate",
    "synopsys-coverity": "coverityIssueCheck withCoverityEnvironment",
    "synopsys-security-scan": "synopsys_scan",
    "sysdig-secure": "sysdigIaCScan sysdigImageScan",
    "tacotruck": "tacotruck",
    "talend": "createTask runPromotion runTask",
    "tanaguru": "tanaguru",
    "tdoms": "omsChangedFiles omsDeploy omsPush omsReleaseBuildQ",
    "teamconcert": "rtcBuild teamconcert",
    "teamscale-upload": "teamscale",
    "telegram-notifications": "telegramSend",
    "test-results-aggregator": "testResultsAggregator",
    "testdroid-run-in-cloud": "runInCloud",
    "testingbot": "testingbot testingbotChecks testingbotPublisher testingbotTunnel testingbotUpload",
    "testinium": "testiniumExecution",
    "testkube-cli": "setupTestkube",
    "testng-plugin": "testNG",
    "testweaver": "testweaver",
    "testwheel-trigger": "testwheelTrigger",
    "text-finder": "findText",
    "themis": "themisRefresh themisReport",
    "throttle-concurrents": "throttle",
    "thycotic-devops-secrets-vault": "dsvSecret",
    "thycotic-secret-server": "withSecretServer",
    "tics": "publishTicsResults runTics",
    "timestamper": "timestamps",
    "tm4j-automation": "downloadFeatureFiles publishTestResults",
    "token-macro": "tm",
    "tricentis-ci": "tricentisCI",
    "tuleap-api": "tuleapNotifyCommitStatus tuleapSendTTMResults",
    "typetalk": "typetalkSend withTypetalk",
    "uipath-automation-package": "UiPathAssets UiPathDeploy UiPathInstallPlatform UiPathPack UiPathRunJob UiPathSolutionActivateDeployment UiPathSolutionDeletePackage UiPathSolutionDeploy UiPathSolutionDownloadConfig UiPathSolutionDownloadPackage UiPathSolutionPack UiPathSolutionUninstallDeployment UiPathSolutionUploadPackage UiPathTest",
    "uleska": "uleskaScanner",
    "unblocked": "unblockedNotify",
    "updatebot": "updateBotPush",
    "updraft-publisher": "updraftPublish",
    "upload-pgyer": "pgyerUpload",
    "upx-compress": "upxCompress",
    "user1st-utester": "uTesterPageCountTask uTesterUrlListTask",
    "valgrind": "publishValgrind runValgrind",
    "vdoo-vision": "vdooScan",
    "venafi-codesigning": "venafiCodeSignWithJarSigner venafiCodeSignWithSignTool venafiVerifyWithJarSigner venafiVerifyWithSignTool",
    "venafi-vcert": "venafiVcertRequestCertificate",
    "veracode-scan": "veracode veracodeDynamicAnalysisResubmit veracodeDynamicAnalysisReview veracodeDynamicRescan",
    "versionnumber": "VersionNumber",
    "vigilnz-security": "vigilnzScan",
    "violation-comments-to-github": "ViolationsToGitHub",
    "violation-comments-to-gitlab": "ViolationsToGitLab",
    "violation-comments-to-stash": "ViolationsToBitbucketServer",
    "visual-basic-6": "vb6",
    "visualexpert": "visualexpert",
    "vk-notifier": "vkSend vkSendEnd vkSendStart",
    "vmanager-charts": "vManagerChartHelper",
    "vmanager-plugin": "vmanagerLaunch vmanagerPostBuildActions",
    "vrealize-automation-8": "vraDeleteDeployment vraDeployFromCatalog vraGetDeployment vraGetResourceActionDetails vraGetResourceActions vraRunAction vraWaitForAddress",
    "vsphere-cloud": "vSphere",
    "vstestrunner": "vsTest",
    "vulnerability-vines-ai": "vinesScan",
    "waptpro": "waptProReport",
    "warnings-ng": "publishIssues recordIssues scanForIssues",
    "wattspeed": "wattspeed",
    "wavefront": "wavefrontTimedCall",
    "webhook-step": "registerWebhook waitForWebhook",
    "websocketin-notification": "websocketin",
    "wetest-automation": "wetest",
    "windows-azure-storage": "azureDownload azureUpload",
    "windows-exe-runner": "runexe",
    "winrm-client": "winRMClient",
    "wiz-scanner": "wizcli",
    "workflow-basic-steps": "archive catchError deleteDir echo error fileExists getContext isUnix mail pwd readFile retry sleep stash step timeout tool unarchive unstable unstash waitUntil warnError withContext withEnv wrap writeFile",
    "workflow-cps": "load parallel",
    "workflow-durable-task-step": "bat dir node powershell pwsh sh ws",
    "workflow-multibranch": "properties readTrusted resolveScm",
    "workflow-scm-step": "checkout readScmFile",
    "worktile": "pingcodeBuildRecord pingcodeDeployRecord worktileBuildRecord worktileDeployRecord",
    "ws-cleanup": "cleanWs",
    "ws-ctm": "execMan",
    "wxwork-notification": "wxwork",
    "xcode-plugin": "exportIpa importDeveloperProfile unlockMacOSKeychain xcodeBuild",
    "xlrelease-plugin": "xlrCreateRelease",
    "xml-editor": "xmlEdit xmlEditBuilder xmlQuery xmlRead xmlValidate xmlValidateBuilder",
    "xooa": "xooa",
    "xunit": "xunit",
    "xvnc": "xvnc",
    "xygeni-sensor": "xygeniSaltAtAdd xygeniSaltAtCommit xygeniSaltAtInit xygeniSaltAtRun xygeniSaltAtSlsa xygeniSaltVerify",
    "yet-another-docker-plugin": "dockerShell",
    "z-bom": "zbomScan",
    "zap-pipeline": "archiveZap configurePassiveRules importZapScanPolicy importZapUrls runZapAttack runZapCrawler startZap stopZap",
    "zdevops": "allocateDS deleteDataset deleteDatasetsByMask downloadDS performTsoCommand submitJob submitJobSync writeFileToDS writeFileToFile writeFileToMember writeToDS writeToFile writeToMember zosmf",
    "zephyr-enterprise-test-management": "zeeReporter",
    "zerobug": "ZeroBugPublisher",
    "zohoqengine": "zohoQEngineTestPlanExecution",
    "zohosprints": "addSprintComment addSprintsReleaseComment completeSprint createSprints sprintsAddFeedStatus sprintsAddWorkItem sprintsAddWorkItemComment sprintsCreateRelease sprintsUpdateRelease sprintsUpdateWorkItem startSprint updateSprints",
    "zoom": "zoomSend",
    "zscan-upload": "zScanUpload",
    "zulip": "zulipNotification zulipSend",
}

#: The names of the dictionary, flattened.
JENKINS_STEP_NAMES = frozenset(
    name
    for names in JENKINS_STEPS_BY_PLUGIN.values()
    for name in names.split()
)

# --- The tree the guard reads -------------------------------------------------

#: The source sets the guard reads: the production code of the product and of
#: the plugins of the examples. A test may name a step the product does not
#: have, and that is the business of the test.
PRODUCTION_MARKER = "/src/main/kotlin/"

#: The roots of the tree the guard walks.
TREE_ROOTS = ("pipelinek", "examples")

#: The directories the guard does not walk: they hold generated code.
SKIPPED_DIRECTORIES = ("build", ".git", ".gradle", "out", "node_modules")


def production_files(repo: Path) -> list[Path]:
    """Every production source of the tree, in a stable order."""
    found: list[Path] = []
    for root in TREE_ROOTS:
        base = repo / root
        if not base.is_dir():
            continue
        for directory, subdirectories, names in os.walk(base):
            subdirectories[:] = [d for d in subdirectories if d not in SKIPPED_DIRECTORIES]
            for name in names:
                if not name.endswith(".kt"):
                    continue
                path = Path(directory) / name
                if PRODUCTION_MARKER in path.as_posix():
                    found.append(path)
    return sorted(found)


def masked(text: str) -> str:
    """The text with its strings and its comments blanked.

    The blanks keep the length of the text, so a span of the masked text is
    the same span of the original one. A reader that looks for a shape can
    therefore look at the masked text and read the VALUE at the same offsets
    of the original: a parenthesis inside a comment does not close a block,
    and a parenthesis inside a string does not either.
    """
    out = list(text)
    length = len(text)
    index = 0
    while index < length:
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            end = length if end < 0 else end + 3
            for position in range(index, end):
                out[position] = " "
            index = end
            continue
        if text[index] == '"':
            end = index + 1
            while end < length:
                if text[end] == "\\":
                    end += 2
                    continue
                if text[end] == '"':
                    break
                end += 1
            for position in range(index + 1, min(end, length)):
                out[position] = " "
            index = end + 1
            continue
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end < 0 else end
            for position in range(index, end):
                out[position] = " "
            index = end
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            for position in range(index, end):
                out[position] = " "
            index = end
            continue
        index += 1
    return "".join(out)


def matching(masked_text: str, opener: int, open_char: str, close_char: str) -> int:
    """The index of the closer that matches the opener, or -1."""
    depth = 0
    for index in range(opener, len(masked_text)):
        character = masked_text[index]
        if character == open_char:
            depth += 1
        elif character == close_char:
            depth -= 1
            if depth == 0:
                return index
    return -1


def without_groups(text: str) -> str:
    """The text with its parentheses and its brackets blanked.

    The brackets of a primary constructor hold the names of its parameters,
    and a parameter may be declared with the word that opens a declaration.
    The words inside the brackets are not declarations of the file, so they
    are blanked before the guard looks for them.
    """
    out = list(text)
    depth = 0
    for index, character in enumerate(text):
        if character in "([":
            depth += 1
        elif character in ")]":
            depth = max(0, depth - 1)
        elif depth > 0:
            out[index] = " "
    return "".join(out)


#: The words that open a declaration: what stands between the name of a
#: declaration and the brace that follows it may not hold one of them, because
#: then the brace belongs to THAT declaration and not to this one.
DECLARATION_WORD = re.compile(
    r"\b(?:annotation|class|companion|constructor|data|enum|fun|init|interface"
    r"|object|typealias|val|var)\b"
)


class Braces:
    """The braces of a file, each with the brace that closes it."""

    def __init__(self, masked_text: str) -> None:
        stack: list[int] = []
        pairs: list[tuple[int, int]] = []
        for index, character in enumerate(masked_text):
            if character == "{":
                stack.append(index)
            elif character == "}":
                if stack:
                    pairs.append((stack.pop(), index))
        pairs.sort()
        self.pairs = pairs
        self.openers = [opener for opener, _ in pairs]
        self.closers = [closer for _, closer in pairs]

    def depth(self, offset: int) -> int:
        """How many braces are open at the offset."""
        import bisect

        return bisect.bisect_left(self.openers, offset) - bisect.bisect_right(self.closers, offset)


def declaration_bodies(masked_text: str) -> list[tuple[str, int, int]]:
    """The declarations of a file, with the span of the body of each.

    A declaration may have NO body at all --a data class with a primary
    constructor and nothing else-- and then the brace that follows belongs to
    the NEXT declaration. The guard tells them apart by the depth they stand
    at and by what stands between the name and the brace: a constructor and a
    supertype list may, and another declaration may not.
    """
    braces = Braces(masked_text)
    stripped = without_groups(masked_text)
    words = [match.start() for match in DECLARATION_WORD.finditer(stripped)]
    bodies: list[tuple[str, int, int]] = []
    for match in DECLARATION.finditer(masked_text):
        name_end = match.end()
        depth = braces.depth(match.start())
        for opener, closer in braces.pairs:
            if opener <= name_end:
                continue
            if braces.depth(opener) != depth:
                continue
            position = bisect.bisect_right(words, name_end)
            if position < len(words) and words[position] < opener:
                continue
            bodies.append((match.group(1), opener + 1, closer))
            break
    return bodies


# --- The facts a row declares -------------------------------------------------

#: A fact that is a single text: the equivalent in Jenkins, a name.
LITERAL = "literal"

#: A fact that is a text built out of several texts: the motive of a divergence.
TEXT = "text"

#: A fact the tree computes: the name of the function that computes it.
CALL = "call"

#: A fact the guard cannot read: the name of something it does not know.
NAME = "name"

#: The absence of a fact.
ABSENT = "absent"


@dataclass(frozen=True)
class Fact:
    """One fact a row declares, and how the guard read it."""

    kind: str
    value: str


@dataclass(frozen=True)
class Row:
    """One row of the table: what the tree declares about one entry."""

    key: str
    name: str
    surface: Fact
    divergence: Fact
    origin: str
    kind: str


@dataclass
class Inspection:
    """What the guard finds in a tree."""

    rows: list[Row] = field(default_factory=list)
    keys: set[str] = field(default_factory=set)
    names: set[str] = field(default_factory=set)
    findings: list[str] = field(default_factory=list)
    files: int = 0
    skipped: int = 0


def read_expression(mask: str, original: str, start: int) -> Fact:
    """The expression that starts at [start] of the masked text."""
    index = start
    length = len(mask)
    while index < length and mask[index] in " \t\r\n":
        index += 1
    if index >= length:
        return Fact(ABSENT, "")
    if mask[index] == '"':
        parts: list[str] = []
        while True:
            if index >= length or mask[index] != '"':
                break
            end = index + 1
            while end < length:
                if original[end] == "\\":
                    end += 2
                    continue
                if original[end] == '"':
                    break
                end += 1
            parts.append(original[index + 1:end])
            index = end + 1
            following = index
            while following < length and mask[following] in " \t\r\n":
                following += 1
            if following < length and mask[following] == "+":
                index = following + 1
                continue
            break
        joined = "".join(parts)
        return Fact(LITERAL if len(parts) == 1 else TEXT, joined)
    qualified = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*").match(mask, index)
    if qualified is None:
        return Fact(ABSENT, "")
    name = qualified.group(0)
    after = qualified.end()
    while after < length and mask[after] in " \t\r\n":
        after += 1
    if after < length and mask[after] == "(":
        return Fact(CALL, name)
    return Fact(NAME, name.replace(" ", ""))


def split_top_level(text: str, separator: str) -> list[str]:
    """The pieces of the text, split at the separators that are not nested."""
    pieces: list[str] = []
    depth = 0
    start = 0
    index = 0
    while index < len(text):
        character = text[index]
        if character in "([{":
            depth += 1
        elif character in ")]}":
            depth -= 1
        elif depth == 0 and text.startswith(separator, index):
            pieces.append(text[start:index])
            index += len(separator)
            start = index
            continue
        index += 1
    pieces.append(text[start:])
    return pieces


#: The table of a function that answers with a when.
WHEN_FUNCTION = re.compile(r"\bfun\s+(\w+)\s*\(")


def when_table(mask: str, original: str, function: str) -> tuple[dict[str, Fact], Fact] | None:
    """The table of a function that answers with a when, and its default.

    The facts of a row may live in a table of the file that declares them, and
    not in the row itself: a row that is generated from a list of keys reads
    its equivalent from a function that answers with a when. The guard reads
    that table, because a table is where the facts of several keys live, and
    an equivalent the guard cannot read is an equivalent the guard cannot
    check.
    """
    for match in WHEN_FUNCTION.finditer(mask):
        if match.group(1) != function:
            continue
        equals = mask.find("=", match.end())
        if equals < 0:
            return None
        keyword = mask.find("when", equals)
        if keyword < 0:
            return None
        opener = mask.find("(", keyword)
        if opener < 0:
            return None
        closer = matching(mask, opener, "(", ")")
        if closer < 0:
            return None
        body_mask = mask[opener + 1:closer]
        body_original = original[opener + 1:closer]
        branches: dict[str, Fact] = {}
        default = Fact(ABSENT, "")
        pieces = split_top_level(body_mask, "->")
        for piece in pieces[1:]:
            condition, _, value = piece.partition("->")
            condition = condition.strip()
            fact = read_expression(body_mask, body_original, body_mask.find(value, body_mask.find(condition) + len(condition)))
            if condition == "else":
                default = fact
                continue
            if fact.kind in (LITERAL, TEXT):
                branches[condition.strip('"')] = fact
        return branches, default
    return None


# --- The names the tree declares -----------------------------------------------

#: The declaration of a key: a step key or a directive key.
KEY_CONSTANT = re.compile(
    r"\bval\s+(\w+)\s*(?::\s*(?:PluginStepId|DirectiveKey))?\s*=\s*"
    r"(?:PluginStepId|DirectiveKey)\s*\(\s*\"([^\"]*)\"\s*\)"
)

#: Every key the tree writes as a literal, in whatever shape it writes it.
KEY_LITERAL = re.compile(r"\b(?:PluginStepId|DirectiveKey)\s*\(\s*\"([^\"]*)\"\s*\)")

#: The declaration of a key that COMPUTES its value.
KEY_GETTER = re.compile(
    r"\bval\s+(\w+)\s*:\s*(?:PluginStepId|DirectiveKey)\s*get\(\)\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)"
)

#: The declaration of a class, of an object or of an interface.
DECLARATION = re.compile(r"\b(?:class|object|interface|enum\s+class)\s+(\w+)")

#: The package of a file.
PACKAGE = re.compile(r"(?m)^\s*package\s+([\w.]+)")

#: An import of a file.
IMPORT = re.compile(r"(?m)^\s*import\s+([\w.]+)")


@dataclass
class Source:
    """One production source of the tree, read."""

    path: Path
    origin: str
    text: str
    mask: str
    package: str
    imports: dict[str, str]
    scopes: list[tuple[str, int, int]]

    def scope_of(self, offset: int) -> str:
        """The name of the declaration the offset belongs to, or the package."""
        best = ""
        best_size = None
        for name, start, end in self.scopes:
            if start <= offset <= end:
                size = end - start
                if best_size is None or size < best_size:
                    best = name
                    best_size = size
        return best or self.package

    def constants(self) -> list[tuple[str, str, str]]:
        """The key constants the file declares: (scope, name, value)."""
        found: list[tuple[str, str, str]] = []
        for match in KEY_CONSTANT.finditer(self.mask):
            value = self.text[match.start(2):match.end(2)]
            found.append((self.scope_of(match.start()), match.group(1), value))
        return found

    def getters(self) -> list[tuple[str, str, str]]:
        """The key properties the file computes: (scope, name, expression)."""
        found: list[tuple[str, str, str]] = []
        for match in KEY_GETTER.finditer(self.mask):
            found.append((self.scope_of(match.start()), match.group(1), match.group(2)))
        return found


def read_source(path: Path, repo: Path) -> Source | None:
    """The source of a file, read and prepared."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    mask = masked(text)
    scopes: list[tuple[str, int, int]] = declaration_bodies(mask)
    package_match = PACKAGE.search(text)
    imports: dict[str, str] = {}
    for match in IMPORT.finditer(text):
        qualified = match.group(1)
        imports[qualified.rsplit(".", 1)[-1]] = qualified
    return Source(
        path=path,
        origin=path.relative_to(repo).as_posix(),
        text=text,
        mask=mask,
        package=package_match.group(1) if package_match else "",
        imports=imports,
        scopes=scopes,
    )


def package_and_name(qualified: str) -> tuple[str, str]:
    """The package and the name of a qualified name."""
    pieces = qualified.split(".")
    return ".".join(pieces[:-1]), pieces[-1]


def resolve(
    expression: str,
    scope: str,
    source: Source,
    constants: dict[tuple[str, str], str],
    getters: dict[tuple[str, str], str],
    seen: frozenset[tuple[str, str]] = frozenset(),
) -> str | None:
    """The key an expression names, when the tree declares it.

    A key may be COMPUTED: a property may hold the key of another one. The
    guard follows the computation, because a key it cannot resolve is a key
    whose row it cannot check.
    """
    base = expression
    while base.endswith(".value"):
        base = base[: -len(".value")]
    candidates: list[tuple[str, str]] = []
    if "." in base:
        parts = base.split(".")
        candidates.append((parts[-2], parts[-1]))
        head = parts[0]
        if head in source.imports:
            candidates.append(package_and_name(source.imports[head]))
    else:
        candidates.append((scope, base))
        if base in source.imports:
            candidates.append(package_and_name(source.imports[base]))
        candidates.append((source.package, base))
    for candidate in candidates:
        value = constants.get(candidate)
        if value is not None:
            return value
        computed = getters.get(candidate)
        if computed is not None and candidate not in seen:
            return resolve(
                computed, candidate[0], source, constants, getters, seen | {candidate}
            )
    return None
# --- The rows the tree declares ------------------------------------------------

#: The type parameters of a declaration, with the nesting of one level.
GENERIC = r"<(?:[^<>()]|<[^<>]*>)*>"

#: The contract of a step: the row the catalogue publishes for it.
CONTRACT_CALL = re.compile(r"\bStepContract\s*(?:" + GENERIC + r")?\s*\(")

#: The declaration of the contract itself: it is not a row. The head of the
#: declaration ends right before the name, so the call that follows it is the
#: constructor of the class and not the contract of a step.
CONTRACT_DECLARATION = re.compile(r"\bclass\s+$")

#: The descriptor of a step.
DESCRIPTOR_CALL = re.compile(r"\bStepDescriptor\s*\(")

#: The declaration of the descriptor itself: it is not a row.
DESCRIPTOR_DECLARATION = re.compile(r"\bclass\s+StepDescriptor\b")

#: The row the domain declares without a registered handler.
REGISTRY_ROW = re.compile(
    r"\bPluginStepId\s*\(\s*\"([^\"]*)\"\s*\)\s*to\s+StepDescriptor\b[^(]*\("
)

#: The fields a descriptor declares.
FIELD = re.compile(r"\b(stepId|name|jenkinsSurface|jenkinsDivergence)\s*=\s*")

#: The key a contract names.
CONTRACT_KEY = re.compile(r"\bkey\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)")

#: The descriptor a contract carries: the call that builds it, or the property
#: that holds it.
CONTRACT_DESCRIPTOR = re.compile(r"\bdescriptor\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)")

#: The property that holds a descriptor.
PROPERTY_DECLARATION = re.compile(r"\b(?:val|var)\s+(\w+)\s*(?::[^=]*)?=\s*$")

#: The key of a directive.
DIRECTIVE_KEY = re.compile(
    r"\boverride\s+val\s+key\s*:\s*DirectiveKey\s*(?:get\(\)\s*)?=\s*([A-Za-z_][A-Za-z0-9_.]*)"
)

#: A fact of a directive.
DIRECTIVE_FACT = re.compile(
    r"\boverride\s+val\s+(jenkinsSurface|jenkinsDivergence)\s*:\s*String\s*"
    r"(?:get\(\)\s*)?=\s*"
)


def descriptor_facts(mask: str, original: str, opener: int) -> dict[str, Fact]:
    """The facts the descriptor that starts at the opener declares."""
    closer = matching(mask, opener, "(", ")")
    if closer < 0:
        return {}
    body_mask = mask[opener + 1:closer]
    body_original = original[opener + 1:closer]
    facts: dict[str, Fact] = {}
    for match in FIELD.finditer(body_mask):
        name = match.group(1)
        if name in facts:
            continue
        facts[name] = read_expression(body_mask, body_original, match.end())
    return facts


def expand(
    surface: Fact,
    divergence: Fact,
    mask: str,
    original: str,
) -> list[tuple[str, Fact, Fact]]:
    """The rows a descriptor declares, when its facts live in a table.

    A row whose facts are computed covers every key the table names: the
    table is where the facts of several keys live, and the guard reads it
    key by key.
    """
    tables: list[tuple[dict[str, Fact], Fact]] = []
    for fact in (surface, divergence):
        if fact.kind != CALL:
            continue
        table = when_table(mask, original, fact.value)
        if table is not None:
            tables.append(table)
    if not tables:
        return []
    keys: list[str] = []
    for branches, _ in tables:
        for key in branches:
            if key not in keys:
                keys.append(key)
    rows: list[tuple[str, Fact, Fact]] = []
    for key in keys:
        surfaces = Fact(ABSENT, "")
        divergences = Fact(ABSENT, "")
        for branches, default in tables:
            value = branches.get(key, default)
            if surface.kind == CALL:
                surfaces = value
            if divergence.kind == CALL:
                divergences = value
        rows.append((key, surfaces, divergences))
    return rows


def rows_of(
    source: Source,
    constants: dict[tuple[str, str], str],
    getters: dict[tuple[str, str], str],
) -> tuple[list[Row], list[str], int]:
    """The rows a file declares, the findings about them, and what it skips."""
    rows: list[Row] = []
    findings: list[str] = []
    skipped = 0
    mask = source.mask
    text = source.text

    # The descriptors of the file, by the property that holds each one.
    properties: dict[tuple[str, str], int] = {}
    for match in DESCRIPTOR_CALL.finditer(mask):
        declaration = PROPERTY_DECLARATION.search(mask[:match.start()])
        if declaration is not None:
            owner = source.scope_of(match.start())
            properties[(owner, declaration.group(1))] = match.end() - 1

    declarations = {match.start() for match in DESCRIPTOR_DECLARATION.finditer(mask)}


    for match in CONTRACT_CALL.finditer(mask):
        if CONTRACT_DECLARATION.search(mask[:match.start()]):
            continue
        opener = match.end() - 1
        closer = matching(mask, opener, "(", ")")
        if closer < 0:
            continue
        body_mask = mask[opener + 1:closer]
        body_original = text[opener + 1:closer]
        scope = source.scope_of(match.start())

        key_match = CONTRACT_KEY.search(body_mask)
        if key_match is None:
            findings.append(
                f"{source.origin}: a contract declares no key, so the catalogue cannot publish it"
            )
            continue
        expression = key_match.group(1)
        key = resolve(expression, scope, source, constants, getters)
        if key is None:
            findings.append(
                f"{source.origin}: the contract names the key {expression} and the tree "
                "declares no such key, so the row cannot be read"
            )
            continue

        descriptor_match = CONTRACT_DESCRIPTOR.search(body_mask)
        if descriptor_match is None:
            findings.append(
                f"{source.origin}: the step {key} declares no descriptor, so the catalogue "
                "publishes no entry for it"
            )
            continue
        expression = descriptor_match.group(1)
        if expression == "StepDescriptor":
            inline = opener + 1 + descriptor_match.end()
            while inline < len(mask) and mask[inline] in " \t\r\n":
                inline += 1
            facts = descriptor_facts(mask, text, inline)
        else:
            position = properties.get((scope, expression))
            if position is None:
                findings.append(
                    f"{source.origin}: the step {key} names the descriptor {expression} and the "
                    "tree declares no such descriptor, so the catalogue publishes no entry for it"
                )
                continue
            facts = descriptor_facts(mask, text, position)

        step_id = facts.get("stepId", Fact(ABSENT, ""))
        if step_id.kind == LITERAL and step_id.value != key:
            findings.append(
                f"{source.origin}: the contract of {key} carries a descriptor that describes "
                f"{step_id.value}, so the table promises a step the tree does not have"
            )
            continue
        name = facts.get("name", Fact(ABSENT, ""))
        rows.append(
            Row(
                key=key,
                name=name.value if name.kind in (LITERAL, TEXT) else "",
                surface=facts.get("jenkinsSurface", Fact(ABSENT, "")),
                divergence=facts.get("jenkinsDivergence", Fact(ABSENT, "")),
                origin=f"{source.origin}: a step",
                kind="step",
            )
        )

    for match in REGISTRY_ROW.finditer(mask):
        if match.start() in declarations:
            continue
        opener = match.end() - 1
        facts = descriptor_facts(mask, text, opener)
        surface = facts.get("jenkinsSurface", Fact(ABSENT, ""))
        divergence = facts.get("jenkinsDivergence", Fact(ABSENT, ""))
        expanded = expand(surface, divergence, mask, text)
        if expanded:
            for expanded_key, expanded_surface, expanded_divergence in expanded:
                rows.append(
                    Row(
                        key=expanded_key,
                        name="",
                        surface=expanded_surface,
                        divergence=expanded_divergence,
                        origin=f"{source.origin}: a declared row",
                        kind="step",
                    )
                )
            continue
        key = literal_at(text, match.start(1), match.end(1))
        name = facts.get("name", Fact(ABSENT, ""))
        rows.append(
            Row(
                key=key,
                name=name.value if name.kind in (LITERAL, TEXT) else "",
                surface=surface,
                divergence=divergence,
                origin=f"{source.origin}: a declared row",
                kind="step",
            )
        )

    for name, body_start_offset, body_end_offset in source.scopes:
        body_mask = mask[body_start_offset:body_end_offset]
        body_text = text[body_start_offset:body_end_offset]
        key_match = DIRECTIVE_KEY.search(body_mask)
        if key_match is None:
            continue
        expression = key_match.group(1)
        key = resolve(expression, name, source, constants, getters)
        if key is None:
            skipped += 1
            continue
        facts: dict[str, Fact] = {}
        for fact_match in DIRECTIVE_FACT.finditer(body_mask):
            field = fact_match.group(1)
            if field in facts:
                continue
            facts[field] = read_expression(body_mask, body_text, fact_match.end())
        rows.append(
            Row(
                key=key,
                name=key.rsplit(".", 1)[-1],
                surface=facts.get("jenkinsSurface", Fact(ABSENT, "")),
                divergence=facts.get("jenkinsDivergence", Fact(ABSENT, "")),
                origin=f"{source.origin}: a directive",
                kind="directive",
            )
        )

    return rows, findings, skipped


def literal_at(original: str, start: int, end: int) -> str:
    """The text of a literal, read from the original."""
    return original[start:end]


def inspect(repo: Path) -> Inspection:
    """What the guard finds in a tree."""
    result = Inspection()

    sources: list[Source] = []
    for path in production_files(repo):
        source = read_source(path, repo)
        if source is not None:
            sources.append(source)
    result.files = len(sources)

    constants: dict[tuple[str, str], set[str]] = {}
    declared: set[str] = set()
    for source in sources:
        for scope, name, value in source.constants():
            constants.setdefault((scope, name), set()).add(value)
            declared.add(value)
        for match in KEY_LITERAL.finditer(source.mask):
            declared.add(source.text[match.start(1):match.end(1)])
    resolved_constants = {
        key: next(iter(values)) for key, values in constants.items() if len(values) == 1
    }
    computed: dict[tuple[str, str], set[str]] = {}
    for source in sources:
        for scope, name, expression in source.getters():
            computed.setdefault((scope, name), set()).add(expression)
    resolved_getters = {
        key: next(iter(values)) for key, values in computed.items() if len(values) == 1
    }

    for source in sources:
        rows, findings, skipped = rows_of(source, resolved_constants, resolved_getters)
        result.rows.extend(rows)
        result.findings.extend(findings)
        result.skipped += skipped

    result.keys = declared
    for row in result.rows:
        if row.name:
            result.names.add(row.name)
        result.names.add(row.key.rsplit(".", 1)[-1])
    return result


# --- What the guard says about the rows ---------------------------------------

def check_rows(result: Inspection) -> None:
    """The first leg and the second: the shape of every row of the table."""
    for row in result.rows:
        surface = row.surface
        divergence = row.divergence

        if surface.kind == CALL:
            result.findings.append(
                f"{row.origin}: the row of {row.key} computes its equivalent and the guard "
                "cannot read the table that holds it"
            )
        elif surface.kind in (LITERAL, TEXT) and surface.value:
            if surface.kind == TEXT:
                result.findings.append(
                    f"{row.origin}: the equivalent of {row.key} is built out of several texts, "
                    "so it is not the name of a single step of Jenkins"
                )
            elif surface.value not in JENKINS_STEP_NAMES:
                result.findings.append(
                    f"{row.origin}: the equivalent {surface.value} of {row.key} is not a step "
                    "that Jenkins publishes"
                )

        if not surface.value and not divergence.value:
            result.findings.append(
                f"{row.origin}: the row of {row.key} declares no equivalent and no divergence, "
                "so the table says nothing about it"
            )

        if row.key not in result.keys:
            result.findings.append(
                f"{row.origin}: the row of {row.key} names a key the tree does not declare, "
                "so the table promises a step the tree does not have"
            )


# --- What the guard says about the READMEs -------------------------------------

#: The command whose output a README shows as a sample.
SAMPLE_HEADER = "$ pipelinek steps"

#: The rows of that sample.
SAMPLE_ROW = re.compile(r"^(directive|step)\s+(\S+)\s+(\S+)\s*$")

#: The card a README shows.
CARD_HEADER = re.compile(r"^\$ pipelinek describe\s+(\S+)\s*$")

#: A field of that card.
CARD_FIELD = re.compile(r"^\s+(jenkins|divergence)\s+(.*?)\s*$")

#: The value the card prints when a field has none.
NO_VALUE = "(none)"


def sample_rows(text: str) -> list[tuple[str, str]]:
    """The rows of the sample of the index that a README carries."""
    rows: list[tuple[str, str]] = []
    inside = False
    for line in text.splitlines():
        if line.strip() == SAMPLE_HEADER:
            inside = True
            continue
        if not inside:
            continue
        if not line.strip():
            continue
        match = SAMPLE_ROW.match(line.strip())
        if match is None:
            break
        rows.append((match.group(2), match.group(3)))
    return rows


def sample_card(text: str) -> tuple[str, dict[str, str]] | None:
    """The card a README carries, and the fields it prints."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = CARD_HEADER.match(line.strip())
        if match is None:
            continue
        key = ""
        fields: dict[str, str] = {}
        for candidate in lines[index + 1:]:
            if not candidate.strip():
                break
            if not candidate.startswith(" ") and not key:
                key = candidate.strip()
                continue
            field = CARD_FIELD.match(candidate)
            if field is not None:
                fields[field.group(1)] = field.group(2)
        if key:
            return key, fields
    return None


def check_readmes(repo: Path, result: Inspection) -> int:
    """The third leg: the READMEs publish what the tree declares."""
    checked = 0
    catalogue = {row.key: row for row in result.rows}

    for name in ("README.md", "README.es.md"):
        path = repo / name
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue

        for key, sample_name in sample_rows(text):
            checked += 1
            row = catalogue.get(key)
            if row is None:
                result.findings.append(
                    f"{name}: the sample of the index names {key}, and the tree has no such row"
                )
                continue
            if row.name and sample_name != row.name:
                result.findings.append(
                    f"{name}: the sample of the index names {key} as {sample_name}, and the "
                    f"tree calls it {row.name}"
                )

        card = sample_card(text)
        if card is not None:
            checked += 1
            key, fields = card
            row = catalogue.get(key)
            if row is None:
                result.findings.append(
                    f"{name}: the card names {key}, and the tree has no such row"
                )
                continue
            printed = fields.get("jenkins")
            if printed is not None:
                expected = row.surface.value if row.surface.value else NO_VALUE
                if printed != expected:
                    result.findings.append(
                        f"{name}: the card of {key} prints the equivalent {printed}, and the "
                        f"tree declares {expected}"
                    )
            printed = fields.get("divergence")
            if printed is not None and printed != NO_VALUE and not row.divergence.value:
                result.findings.append(
                    f"{name}: the card of {key} prints a divergence, and the tree declares none"
                )
    return checked


# --- The control of the guard --------------------------------------------------

CONTROL_SOURCE = """
package fake

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.step.StepContract

object FakeKeys {
    val INVENTED: PluginStepId = PluginStepId("fake.invented")
    val SILENT: PluginStepId = PluginStepId("fake.silent")
}

class InventedDefinition {
    val contract = StepContract(
        key = FakeKeys.INVENTED,
        descriptor = StepDescriptor(
            stepId = "fake.invented",
            name = "invented",
            jenkinsSurface = "notAStepOfJenkins",
            configRef = "",
        ),
    )
}

class SilentDefinition {
    val contract = StepContract(
        key = FakeKeys.SILENT,
        descriptor = StepDescriptor(
            stepId = "fake.silent",
            name = "silent",
            configRef = "",
        ),
    )
}
"""

CONTROL_README = """
# The fake tree

`pipelinek steps`

```bash
$ pipelinek steps
step       fake.ghost                         ghost
```
"""


def run_control() -> list[str]:
    """What the guard finds in a tree whose table lies.

    The control is the row that says the guard can fail: it writes a tree with
    an invented equivalent, a row that declares no fact and a README whose
    sample names a step nobody has, and it asks the guard whether it finds the
    three.
    """
    problems: list[str] = []
    with tempfile.TemporaryDirectory(prefix="jenkins-surface-control-") as directory:
        repo = Path(directory)
        source = repo / "pipelinek" / "fake" / "src" / "main" / "kotlin" / "fake"
        source.mkdir(parents=True)
        (source / "Fake.kt").write_text(CONTROL_SOURCE, encoding="utf-8")
        (repo / "README.md").write_text(CONTROL_README, encoding="utf-8")

        found = inspect(repo)
        check_rows(found)
        check_readmes(repo, found)

        expectations = {
            "an invented equivalent": "is not a step that Jenkins publishes",
            "a row with no fact": "declares no equivalent and no divergence",
            "a README whose sample names a step nobody has": "the tree has no such row",
        }
        for what, expected in expectations.items():
            if not any(expected in finding for finding in found.findings):
                problems.append(
                    f"the control does not find {what}: the guard would let it through"
                )
    return problems


# --- The verdict ---------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    args = parser.parse_args()
    repo = Path(args.repo).resolve()

    result = inspect(repo)
    check_rows(result)
    sample = check_readmes(repo, result)
    control = run_control()

    equivalents = sum(1 for row in result.rows if row.surface.value)
    divergences = sum(1 for row in result.rows if row.divergence.value)
    entries = len({row.key for row in result.rows})
    print("the table of equivalences with Jenkins")
    print(f"  files read:      {result.files}")
    print(f"  rows read:       {len(result.rows)}")
    print(f"  entries:         {entries}")
    print(f"  keys declared:   {len(result.keys)}")
    print(f"  equivalents:     {equivalents}")
    print(f"  divergences:     {divergences}")
    print(f"  not a row:       {result.skipped}")
    print(f"  samples read:    {sample}")
    print(f"  dictionary:      {len(JENKINS_STEP_NAMES)} steps of Jenkins, read on "
          f"{JENKINS_STEPS_READ_ON}")

    if control:
        print("  FAILURES:   the control of the guard")
        for problem in control:
            print(f"    - {problem}")
        return 1

    if result.findings:
        print(f"  FAILURES:   {len(result.findings)}")
        for finding in result.findings:
            print(f"    - {finding}")
        return 1

    print("  every equivalent is a step that Jenkins publishes, every step of the tree has an")
    print("  entry that declares a fact, and the READMEs publish what the tree declares")
    print("  the control of the guard finds the three offences it writes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
