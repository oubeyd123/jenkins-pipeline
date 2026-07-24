pipeline {
    agent none

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        REGISTRY               = 'docker.io/oubeyd' 
        REGISTRY_CRED_ID       = 'dockerhub-token'           
        BUILD_AGENT_LABEL      = ''
        DEV_DEPLOY_AGENT_LABEL = 'wso2-dev-server'              
        DEPLOY_AGENT_LABEL     = 'wso2-target-server'          
        SMOKE_BASE_URL         = 'http://localhost:8290'               
    }

    stages {
        stage('Pipeline') {
            agent any
            steps {
                script {
                    checkout scm
                    sh "git config user.name 'jenkins'"
                    sh "git config user.email 'jenkins@ci.local'"

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

                    def branchesMap = [:]
                    apis.each { api ->
                        branchesMap[api.slug] = {
                            stage("Validate: ${api.slug}") {
                                def quality = load 'jenkins/lib/qualityChecks.groovy'
                                quality(api.path)
                            }

                            stage("Test & Package CAR: ${api.slug}") {
                                def build = load 'jenkins/lib/Buildandpackage.groovy'
                                def car = build(api.path)
                                junit allowEmptyResults: true, testResults: "apis/${api.path}/target/surefire-reports/*.xml"
                                archiveArtifacts artifacts: car, fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: "apis/${api.path}/target/*.log"
                            }

                            stage("Docker Build Check: ${api.slug}") {
                                dir("apis/${api.path}") {
                                    sh "docker build -f deployment/docker/Dockerfile -t local/${api.slug}:${env.BUILD_NUMBER} ."
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
                                    node(env.DEV_DEPLOY_AGENT_LABEL) {
                                        def deploy = load 'jenkins/lib/Deploy.groovy'
                                        deploy([apiSlug: api.slug, imageTag: imageTag])
                                    }
                                }

                                stage("Smoke Test Dev: ${api.slug}") {
                                    def smoke = load 'jenkins/lib/smokeTest.groovy'
                                    smoke([
                                        apiSlug: api.slug,
                                        url    : env.SMOKE_BASE_URL ? "${env.SMOKE_BASE_URL}/${api.slug}" : '',
                                    ])
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
                                versioning.tagAndRelease(result.tag, result.changelog)
                            }

                            def imageTag
                            stage("Build & Push Image: ${api.slug}") {
                                def pushImg = load 'jenkins/lib/Buildandpushimage.groovy'
                                imageTag = pushImg([
                                    apiPath              : api.path,
                                    apiSlug              : api.slug,
                                    version              : result.version,
                                    registry             : env.REGISTRY,
                                    registryCredentialsId: env.REGISTRY_CRED_ID,
                                    pushLatest           : true,
                                ])
                            }

                            stage("Approve Production Deploy: ${api.slug}") {
                                input message: "Deploy ${api.slug} ${result.version} to production?"
                            }

                            stage("Deploy: ${api.slug}") {
                                node(env.DEPLOY_AGENT_LABEL) {
                                    def deploy = load 'jenkins/lib/Deploy.groovy'
                                    deploy([apiSlug: api.slug, imageTag: imageTag])
                                }
                            }

                            stage("Smoke Test Production: ${api.slug}") {
                                def smoke = load 'jenkins/lib/smokeTest.groovy'
                                smoke([
                                    apiSlug: api.slug,
                                    url    : env.SMOKE_BASE_URL ? "${env.SMOKE_BASE_URL}/${api.slug}" : '',
                                ])
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
        }
    }
}
