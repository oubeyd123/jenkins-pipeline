pipeline {
    agent none

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        REGISTRY               = 'docker.io/oubeyd' 
        REGISTRY_CRED_ID       = '4865805f-a74b-4c16-a608-99d6194055bc'           
        GIT_CRED_ID            = 'github-token'
        BUILD_AGENT_LABEL      = ''
        DEV_DEPLOY_AGENT_LABEL = 'wso2-dev-server'              
        DEPLOY_AGENT_LABEL     = 'wso2-dev-server'          
        SMOKE_BASE_URL         = 'http://localhost:8290'               
        DEV_CONTAINER_NAME     = 'wso2-mi-dev'
        DEV_CONTAINER_PORTS    = '-p 8290:8290 -p 8253:8253 -p 9164:9164'
        DEV_IMAGE_NAME         = 'wso2-mi-dev'
        TRIVY_WINDOWS_CACHE_DIR = 'C:\\trivy-cache'
        TRIVY_UNIX_CACHE_DIR    = '.trivy-cache'
        FAILURE_EMAIL_RECIPIENTS = 'oubeyd887@gmail.com'
    }

    stages {
        stage('Pipeline') {
            agent any
            steps {
                script {
                    checkout scm
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
                        stage('Validate Changed APIs') {
                            def quality = load 'jenkins/lib/qualityChecks.groovy'
                            apis.each { api ->
                                quality(api.path)
                            }
                        }

                        stage('Validate Runtime API Contexts') {
                            def runtimeChecks = load 'jenkins/lib/runtimeChecks.groovy'
                            runtimeChecks.uniqueApiContexts(apis)
                        }

                        stage('Security Scan Changed APIs') {
                            def security = load 'jenkins/lib/securityChecks.groovy'
                            apis.each { api ->
                                security.fs("apis/${api.path}", api.slug)
                            }
                        }

                        stage('Build Changed CARs') {
                            sh 'rm -rf target/dev-carbonapps && mkdir -p target/dev-carbonapps'

                            def build = load 'jenkins/lib/Buildandpackage.groovy'
                            apis.each { api ->
                                def car = build(api.path)
                                junit allowEmptyResults: true, testResults: "apis/${api.path}/target/surefire-reports/*.xml"
                                archiveArtifacts artifacts: car, fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: "apis/${api.path}/target/*.log"
                                sh "cp '${car}' target/dev-carbonapps/"
                            }
                            stash name: 'dev-docker-context', includes: 'Dockerfile.dev,target/dev-carbonapps/*.car'
                        }

                        def imageTag
                        stage('Build & Push Dev MI Image') {
                            def buildDevImage = load 'jenkins/lib/BuildDevImage.groovy'
                            node(env.DEV_DEPLOY_AGENT_LABEL) {
                                deleteDir()
                                unstash 'dev-docker-context'
                                imageTag = buildDevImage([
                                    imageName            : env.DEV_IMAGE_NAME,
                                    version              : "dev-${env.BUILD_NUMBER}",
                                    registry             : env.REGISTRY,
                                    registryCredentialsId: env.REGISTRY_CRED_ID,
                                    commitSha            : env.SOURCE_COMMIT,
                                    sourceUrl            : env.SOURCE_URL,
                                ])
                            }
                        }

                        stage('Trivy Scan Dev Image') {
                            def security = load 'jenkins/lib/securityChecks.groovy'
                            node(env.DEV_DEPLOY_AGENT_LABEL) {
                                security.image(imageTag, env.DEV_IMAGE_NAME)
                            }
                        }

                        stage('Deploy Dev MI Container') {
                            def deploy = load 'jenkins/lib/Deploy.groovy'
                            node(env.DEV_DEPLOY_AGENT_LABEL) {
                                deploy([
                                    apiSlug      : env.DEV_CONTAINER_NAME,
                                    imageTag     : imageTag,
                                    containerName: env.DEV_CONTAINER_NAME,
                                    ports        : env.DEV_CONTAINER_PORTS,
                                ])
                            }
                        }

                        stage('Smoke Test Changed APIs') {
                            def smokeContexts = sh(
                                script: """
                                    for api_path in ${apis.collect { "'apis/${it.path}/src/main/wso2mi/artifacts/apis'" }.join(' ')}; do
                                      find "\$api_path" -name '*.xml' -print0
                                    done |
                                      xargs -0 -r sed -n 's/.*<api[^>]* context="\\([^"]*\\)".*/\\1/p' |
                                      sort -u
                                """,
                                returnStdout: true
                            ).trim()
                            def smoke = load 'jenkins/lib/smokeTest.groovy'
                            node(env.DEV_DEPLOY_AGENT_LABEL) {
                                smoke([
                                    apiSlug : env.DEV_CONTAINER_NAME,
                                    contexts: smokeContexts,
                                    baseUrl : env.SMOKE_BASE_URL,
                                ])
                            }
                        }

                        return
                    }

                    def branchesMap = [:]
                    apis.each { api ->
                        branchesMap[api.slug] = {
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
                                def car = build(api.path)
                                junit allowEmptyResults: true, testResults: "apis/${api.path}/target/surefire-reports/*.xml"
                                archiveArtifacts artifacts: car, fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: "apis/${api.path}/target/*.log"
                                stash name: "docker-check-${api.slug}", includes: "apis/${api.path}/deployment/docker/**,apis/${api.path}/target/*.car"
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

                            if (env.IS_DEVELOP == 'true') {
                                def imageTag

                                stage("Build & Push Dev Image: ${api.slug}") {
                                    def pushImg = load 'jenkins/lib/Buildandpushimage.groovy'
                                    imageTag = pushImg([
                                        apiPath              : api.path,
                                        apiSlug              : api.slug,
                                        version              : "dev-${env.BUILD_NUMBER}",
                                        registry             : env.REGISTRY,
                                        registryCredentialsId: env.REGISTRY_CRED_ID,
                                        pushLatest           : false,
                                    ])
                                }

                                stage("Deploy Dev: ${api.slug}") {
                                    def deploy = load 'jenkins/lib/Deploy.groovy'
                                    node(env.DEV_DEPLOY_AGENT_LABEL) {
                                        deploy([
                                            apiSlug      : api.slug,
                                            imageTag     : imageTag,
                                            containerName: env.DEV_CONTAINER_NAME,
                                            ports        : env.DEV_CONTAINER_PORTS,
                                        ])
                                    }
                                }

                                stage("Smoke Test Dev: ${api.slug}") {
                                    def smokeContexts = sh(
                                        script: """
                                            find 'apis/${api.path}/src/main/wso2mi/artifacts/apis' -name '*.xml' -print0 |
                                              xargs -0 -r sed -n 's/.*<api[^>]* context="\\([^"]*\\)".*/\\1/p' |
                                              sort -u
                                        """,
                                        returnStdout: true
                                    ).trim()
                                    def smoke = load 'jenkins/lib/smokeTest.groovy'
                                    node(env.DEV_DEPLOY_AGENT_LABEL) {
                                        smoke([
                                            apiSlug : api.slug,
                                            contexts: smokeContexts,
                                            baseUrl : env.SMOKE_BASE_URL,
                                        ])
                                    }
                                }

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
                                def smokeContexts = sh(
                                    script: """
                                        find 'apis/${api.path}/src/main/wso2mi/artifacts/apis' -name '*.xml' -print0 |
                                          xargs -0 -r sed -n 's/.*<api[^>]* context="\\([^"]*\\)".*/\\1/p' |
                                          sort -u
                                    """,
                                    returnStdout: true
                                ).trim()
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
