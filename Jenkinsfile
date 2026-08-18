pipeline {
    agent none

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        quietPeriod(0)
        timestamps()
    }

    environment {
        BUILD_AGENT_LABEL = 'built-in'
    }

    stages {
        stage('Pipeline') {
            agent { label "${BUILD_AGENT_LABEL}" }
            steps {
                script {
                    checkout scm
                    def pipelineConfig = load 'jenkins/lib/pipelineConfig.groovy'
                    pipelineConfig()
                    echo "Docker config loaded: registry=${env.REGISTRY}, registryCredential=${env.REGISTRY_CRED_ID}"

                    sh "git config user.name 'jenkins'"
                    sh "git config user.email 'jenkins@ci.local'"
                    env.SOURCE_COMMIT = env.GIT_COMMIT ?: sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    env.SOURCE_URL = env.GIT_URL ?: sh(script: 'git config --get remote.origin.url || true', returnStdout: true).trim()

                    env.IS_PR = env.CHANGE_ID ? 'true' : 'false'
                    env.IS_DEVELOP = (env.BRANCH_NAME == 'develop' && env.CHANGE_ID == null) ? 'true' : 'false'
                    env.IS_RELEASE = (env.BRANCH_NAME == 'main' && env.CHANGE_ID == null) ? 'true' : 'false'

                    def diffRange = env.CHANGE_ID
                        ? "origin/${env.CHANGE_TARGET}...HEAD"
                        : (env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ? "${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT}..HEAD" : 'HEAD')

                    def detect = load 'jenkins/lib/detectChangedApis.groovy'
                    def apis = detect(diffRange)

                    if (apis.isEmpty()) {
                        echo "No API changes detected in range: ${diffRange}"
                        return
                    }

                    echo "Changed APIs: ${apis.collect { it.slug }.join(', ')}"

                    if (env.IS_DEVELOP == 'true') {
                        def developBranchesMap = [:]
                        apis.each { api ->
                            developBranchesMap[api.slug] = {
                                echo "Develop API: slug=${api.slug} path=apis/${api.path}"
                                def devContainerName = "${env.DEV_CONTAINER_NAME}-${api.slug}-${env.BUILD_NUMBER}"
                                def devStashDir = "target/dev-${api.slug}"
                                def devDeployment

                                stage("Validate: ${api.slug}") {
                                    def quality = load 'jenkins/lib/qualityChecks.groovy'
                                    quality(api.path)
                                }

                                stage("Security Scan: ${api.slug}") {
                                    def security = load 'jenkins/lib/securityChecks.groovy'
                                    security.fs("apis/${api.path}", api.slug)
                                }

                                stage("Build CAR: ${api.slug}") {
                                    sh "rm -rf '${devStashDir}' && mkdir -p '${devStashDir}/dev-carbonapps' '${devStashDir}/dev-libs' && touch '${devStashDir}/dev-libs/.dockerkeep'"

                                    def build = load 'jenkins/lib/Buildandpackage.groovy'
                                    def miRuntimeLibs = load 'jenkins/lib/miRuntimeLibs.groovy'
                                    def car = build(api.path)
                                    miRuntimeLibs.prepare(api.path)
                                    junit allowEmptyResults: true, testResults: "apis/${api.path}/target/surefire-reports/*.xml"
                                    archiveArtifacts artifacts: car, fingerprint: true
                                    archiveArtifacts allowEmptyArchive: true, artifacts: "apis/${api.path}/target/*.log"
                                    sh "cp '${car}' '${devStashDir}/dev-carbonapps/'"
                                    sh """
                                        set -euo pipefail
                                        if [ -d 'apis/${api.path}/target/mi-runtime-libs' ]; then
                                          find 'apis/${api.path}/target/mi-runtime-libs' -name '*.jar' -exec cp {} '${devStashDir}/dev-libs/' \\;
                                        fi
                                    """
                                    stash name: "dev-docker-context-${api.slug}", includes: "${devStashDir}/dev-carbonapps/*.car,${devStashDir}/dev-libs/**"
                                }

                                try {
                                    stage("Deploy CAR to Dev MI Container: ${api.slug}") {
                                        def deployDev = load 'jenkins/lib/DeployDevCarbonApp.groovy'
                                        node(env.DEV_DEPLOY_AGENT_LABEL) {
                                            ws("${env.WORKSPACE}@${api.slug}") {
                                                deleteDir()
                                                unstash "dev-docker-context-${api.slug}"
                                                powershell """
                                                    \$ErrorActionPreference = 'Stop'
                                                    New-Item -ItemType Directory -Force -Path target\\dev-carbonapps, target\\dev-libs | Out-Null
                                                    Copy-Item -Path '${devStashDir}\\dev-carbonapps\\*.car' -Destination target\\dev-carbonapps\\ -Force
                                                    if (Test-Path '${devStashDir}\\dev-libs') {
                                                      Copy-Item -Path '${devStashDir}\\dev-libs\\*.jar' -Destination target\\dev-libs\\ -Force -ErrorAction SilentlyContinue
                                                    }
                                                """
                                                devDeployment = deployDev([
                                                    containerName: devContainerName,
                                                    baseImage    : env.WSO2_BASE_IMAGE,
                                                    serverHome   : env.WSO2_SERVER_HOME,
                                                ])
                                            }
                                        }
                                    }

                                    stage("Smoke Test: ${api.slug}") {
                                        def smokeTargets = load 'jenkins/lib/smokeTargets.groovy'
                                        def smokeContexts = smokeTargets(api)
                                        def smoke = load 'jenkins/lib/smokeTest.groovy'
                                        node(env.DEV_DEPLOY_AGENT_LABEL) {
                                            smoke([
                                                apiSlug : devDeployment.containerName,
                                                contexts: smokeContexts,
                                                baseUrl : devDeployment.baseUrl,
                                            ])
                                        }
                                    }
                                } finally {
                                    stage("Clean Dev MI Container: ${api.slug}") {
                                        def cleanupDev = load 'jenkins/lib/CleanupDevContainer.groovy'
                                        node(env.DEV_DEPLOY_AGENT_LABEL) {
                                            cleanupDev(devContainerName)
                                        }
                                    }
                                }
                            }
                        }

                        parallel developBranchesMap
                        return
                    }

                    def branchesMap = [:]
                    apis.each { api ->
                        branchesMap[api.slug] = {
                            echo "Pipeline API: slug=${api.slug} path=apis/${api.path}"
                            def result = null
                            if (env.IS_RELEASE == 'true') {
                                def versioning = load 'jenkins/lib/Versioning.groovy'
                                result = versioning.nextVersion(api.slug, api.path)
                                if (result == null) {
                                    echo "No version bump needed for ${api.slug}; skipping release"
                                    return
                                }
                                echo "Release version for ${api.slug}: ${result.version}"
                            }

                            stage("Validate: ${api.slug}") {
                                def quality = load 'jenkins/lib/qualityChecks.groovy'
                                quality(api.path)
                            }

                            stage("Security Scan: ${api.slug}") {
                                def security = load 'jenkins/lib/securityChecks.groovy'
                                security.fs("apis/${api.path}", api.slug)
                            }

                            stage("Test & Package CAR: ${api.slug}") {
                                def build = load 'jenkins/lib/Buildandpackage.groovy'
                                def miRuntimeLibs = load 'jenkins/lib/miRuntimeLibs.groovy'
                                def car = build(apiPath: api.path, version: result?.version ?: '')
                                miRuntimeLibs.prepare(api.path)
                                junit allowEmptyResults: true, testResults: "apis/${api.path}/target/surefire-reports/*.xml"
                                archiveArtifacts artifacts: car, fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: "apis/${api.path}/target/*.log"
                                stash name: "docker-check-${api.slug}", includes: "apis/${api.path}/deployment/docker/**,apis/${api.path}/target/*.car,apis/${api.path}/target/mi-runtime-libs/**"
                            }

                            stage("Docker Build Check: ${api.slug}") {
                                node(env.DEV_DEPLOY_AGENT_LABEL) {
                                    deleteDir()
                                    unstash "docker-check-${api.slug}"
                                    dir("apis/${api.path}") {
                                        powershell """
                                            \$ErrorActionPreference = 'Stop'
                                            function Invoke-Native {
                                              param([Parameter(ValueFromRemainingArguments = \$true)][object[]]\$Command)
                                              \$exe = \$Command[0]
                                              \$arguments = @()
                                              if (\$Command.Count -gt 1) {
                                                \$arguments = \$Command[1..(\$Command.Count - 1)]
                                              }
                                              & \$exe @arguments
                                              if (\$LASTEXITCODE -ne 0) {
                                                throw "Command failed with exit code \${LASTEXITCODE}: \$Command"
                                              }
                                            }

                                            Invoke-Native docker info
                                            New-Item -ItemType Directory -Force -Path CompositeApps | Out-Null
                                            New-Item -ItemType Directory -Force -Path resources | Out-Null
                                            Copy-Item -Path target\\*.car -Destination CompositeApps\\ -Force
                                            New-Item -ItemType Directory -Force -Path libs | Out-Null
                                            New-Item -ItemType File -Force -Path libs\\.dockerkeep | Out-Null
                                            if (Test-Path target\\mi-runtime-libs) {
                                              Copy-Item -Path target\\mi-runtime-libs\\*.jar -Destination libs\\ -Force -ErrorAction SilentlyContinue
                                            }
                                            if (Test-Path deployment\\docker\\resources) {
                                              Copy-Item -Path deployment\\docker\\resources\\* -Destination resources\\ -Recurse -Force
                                            }

                                            Invoke-Native docker build `
                                              --build-arg BASE_IMAGE='${env.WSO2_BASE_IMAGE}' `
                                              --build-arg WSO2_SERVER_HOME='${env.WSO2_SERVER_HOME}' `
                                              -f deployment/docker/Dockerfile `
                                              -t local/${api.slug}:${env.BUILD_NUMBER} .
                                        """
                                    }
                                }
                            }

                            if (env.IS_PR == 'true') {
                                echo "PR validation completed for ${api.slug}; no image push or deployment will run"
                                return
                            }

                            if (env.IS_RELEASE != 'true') {
                                echo "Validation completed for branch ${env.BRANCH_NAME}; release stages only run on main"
                                return
                            }

                            stage("Version & Tag: ${api.slug}") {
                                def versioning = load 'jenkins/lib/Versioning.groovy'
                                versioning.tagAndRelease(result.tag, result.releaseName, result.changelog, env.GIT_CRED_ID)
                            }

                            def imageTag
                            stage("Build & Push Image: ${api.slug}") {
                                def pushImg = load 'jenkins/lib/Buildandpushimage.groovy'
                                node(env.DEV_DEPLOY_AGENT_LABEL) {
                                    deleteDir()
                                    unstash "docker-check-${api.slug}"
                                    imageTag = pushImg([
                                        apiPath              : api.path,
                                        apiSlug              : api.slug,
                                        version              : result.version,
                                        registry             : env.REGISTRY,
                                        registryCredentialsId: env.REGISTRY_CRED_ID,
                                        pushLatest           : true,
                                        commitSha            : env.SOURCE_COMMIT,
                                        sourceUrl            : env.SOURCE_URL,
                                        baseImage            : env.WSO2_BASE_IMAGE,
                                        serverHome           : env.WSO2_SERVER_HOME,
                                    ])
                                }
                            }

                            stage("Trivy Scan Image: ${api.slug}") {
                                def security = load 'jenkins/lib/securityChecks.groovy'
                                node(env.DEV_DEPLOY_AGENT_LABEL) {
                                    lock(resource: 'trivy-image-cache') {
                                        security.image(imageTag, api.slug)
                                    }
                                }
                            }

                            def deployment
                            stage("Deploy: ${api.slug}") {
                                def deploy = load 'jenkins/lib/Deploy.groovy'
                                node(env.DEPLOY_AGENT_LABEL) {
                                    deployment = deploy([
                                        apiSlug      : api.slug,
                                        imageTag     : imageTag,
                                        containerName: "${api.slug}-release",
                                        serverHome   : env.WSO2_SERVER_HOME,
                                    ])
                                }
                            }

                            stage("Smoke Test Production: ${api.slug}") {
                                def smokeTargets = load 'jenkins/lib/smokeTargets.groovy'
                                def smokeContexts = smokeTargets(api)
                                def smoke = load 'jenkins/lib/smokeTest.groovy'
                                node(env.DEPLOY_AGENT_LABEL) {
                                    smoke([
                                        apiSlug : deployment.containerName,
                                        contexts: smokeContexts,
                                        baseUrl : deployment.baseUrl,
                                    ])
                                }
                            }
                        }
                    }

                    parallel branchesMap
                }
            }
        }
    }

    post {
        always {
            echo "Finished: branch=${env.BRANCH_NAME ?: 'n/a'} release=${env.IS_RELEASE ?: 'n/a'} develop=${env.IS_DEVELOP ?: 'n/a'}"
        }
        failure {
            echo "Failed: branch=${env.BRANCH_NAME ?: 'n/a'} commit=${env.GIT_COMMIT ?: 'n/a'}"
            script {
                try {
                    def aiFailureAnalysis = load 'jenkins/lib/aiFailureAnalysis.groovy'
                    aiFailureAnalysis()
                } catch (err) {
                    echo "AI failure analysis could not run: ${err.message}"
                }

                if ((env.FAILURE_EMAIL_RECIPIENTS ?: '').trim()) {
                    try {
                        def failureDescription = "Pipeline failed with result ${currentBuild.currentResult ?: 'FAILURE'} on branch ${env.BRANCH_NAME ?: 'n/a'}."
                        emailext(
                            to: env.FAILURE_EMAIL_RECIPIENTS,
                            subject: "[Jenkins] FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                            mimeType: 'text/html',
                            attachLog: true,
                            compressLog: true,
                            body: """
                                <p><b>WSO2 MI pipeline failed.</b></p>
                                <p>
                                  <b>Job:</b> ${env.JOB_NAME}<br/>
                                  <b>Build:</b> #${env.BUILD_NUMBER}<br/>
                                  <b>Branch:</b> ${env.BRANCH_NAME ?: 'n/a'}<br/>
                                  <b>Commit:</b> ${env.GIT_COMMIT ?: env.SOURCE_COMMIT ?: 'n/a'}
                                </p>
                                <p>
                                  <b>Failure summary:</b> ${failureDescription}
                                </p>
                                <p>
                                  Open Jenkins build:
                                  <a href="${env.BUILD_URL}">${env.BUILD_URL}</a>
                                </p>
                                <p>The console log is attached.</p>
                            """
                        )
                    } catch (err) {
                        echo "Failure email could not be sent: ${err.message}"
                    }
                }
            }
        }
    }
}
