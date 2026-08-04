pipeline {
    agent none

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
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
                        apis.each { api ->
                            echo "Develop API: slug=${api.slug} path=apis/${api.path}"

                            stage("Validate: ${api.slug}") {
                                def quality = load 'jenkins/lib/qualityChecks.groovy'
                                quality(api.path)
                            }

                            stage("Validate Runtime Artifacts: ${api.slug}") {
                                def runtimeChecks = load 'jenkins/lib/runtimeChecks.groovy'
                                runtimeChecks.uniqueApiContexts([api])
                                runtimeChecks.uniqueLocalEntries([api])
                            }

                            stage("Security Scan: ${api.slug}") {
                                def security = load 'jenkins/lib/securityChecks.groovy'
                                security.fs("apis/${api.path}", api.slug)
                            }

                            stage("Build CAR: ${api.slug}") {
                                sh 'rm -rf target/dev-carbonapps target/dev-libs && mkdir -p target/dev-carbonapps target/dev-libs && touch target/dev-libs/.dockerkeep'

                                def build = load 'jenkins/lib/Buildandpackage.groovy'
                                def miRuntimeLibs = load 'jenkins/lib/miRuntimeLibs.groovy'
                                def car = build(api.path)
                                miRuntimeLibs.prepare(api.path)
                                junit allowEmptyResults: true, testResults: "apis/${api.path}/target/surefire-reports/*.xml"
                                archiveArtifacts artifacts: car, fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: "apis/${api.path}/target/*.log"
                                sh "cp '${car}' target/dev-carbonapps/"
                                sh """
                                    set -euo pipefail
                                    if [ -d 'apis/${api.path}/target/mi-runtime-libs' ]; then
                                      find 'apis/${api.path}/target/mi-runtime-libs' -name '*.jar' -exec cp {} target/dev-libs/ \\;
                                    fi
                                """
                                stash name: "dev-docker-context-${api.slug}", includes: 'Dockerfile.dev,target/dev-carbonapps/*.car,target/dev-libs/**'
                            }

                            stage("Deploy CAR to Dev MI Container: ${api.slug}") {
                                def deployDev = load 'jenkins/lib/DeployDevCarbonApp.groovy'
                                node(env.DEV_DEPLOY_AGENT_LABEL) {
                                    deleteDir()
                                    unstash "dev-docker-context-${api.slug}"
                                    deployDev([
                                        containerName: env.DEV_CONTAINER_NAME,
                                        ports        : env.DEV_CONTAINER_PORTS,
                                        baseImage    : env.WSO2_BASE_IMAGE,
                                        serverHome   : env.WSO2_SERVER_HOME,
                                    ])
                                }
                            }

                            stage("Smoke Test: ${api.slug}") {
                                def smokeTargets = load 'jenkins/lib/smokeTargets.groovy'
                                def smokeContexts = smokeTargets(api)
                                def smoke = load 'jenkins/lib/smokeTest.groovy'
                                node(env.DEV_DEPLOY_AGENT_LABEL) {
                                    smoke([
                                        apiSlug : env.DEV_CONTAINER_NAME,
                                        contexts: smokeContexts,
                                        baseUrl : env.SMOKE_BASE_URL,
                                    ])
                                }
                            }
                        }

                        return
                    }

                    def branchesMap = [:]
                    apis.each { api ->
                        branchesMap[api.slug] = {
                            echo "Pipeline API: slug=${api.slug} path=apis/${api.path}"

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
                                def car = build(api.path)
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
                                            docker info | Out-Host
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

                                            docker build `
                                              --build-arg BASE_IMAGE=wso2/wso2mi:4.6.0 `
                                              --build-arg WSO2_SERVER_HOME=/home/wso2carbon/wso2mi-4.6.0 `
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

                            def versioning = load 'jenkins/lib/Versioning.groovy'
                            def result = versioning.nextVersion(api.slug, api.path)
                            if (result == null) {
                                echo "No version bump needed for ${api.slug}; skipping release"
                                return
                            }

                            stage("Version & Tag: ${api.slug}") {
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
                                    ])
                                }
                            }

                            stage("Trivy Scan Image: ${api.slug}") {
                                def security = load 'jenkins/lib/securityChecks.groovy'
                                node(env.DEV_DEPLOY_AGENT_LABEL) {
                                    security.image(imageTag, api.slug)
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
                if ((env.FAILURE_EMAIL_RECIPIENTS ?: '').trim()) {
                    try {
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
