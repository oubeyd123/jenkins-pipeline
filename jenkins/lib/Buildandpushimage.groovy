def call(Map cfg) {
    def commitSha = cfg.get('commitSha', env.SOURCE_COMMIT ?: env.GIT_COMMIT ?: commandOutput('git rev-parse HEAD'))
    def shortSha = commitSha.take(8)
    def sourceUrl = cfg.get('sourceUrl', env.SOURCE_URL ?: env.GIT_URL ?: remoteUrl())
    def imageRef = "${cfg.registry}/${cfg.apiSlug}"
    def versionTag = cfg.version.startsWith('v') ? "${cfg.version}-${shortSha}" : "v${cfg.version}-${shortSha}"
    def imageTag = "${imageRef}:${versionTag}"
    def pushLatest = cfg.get('pushLatest', false)
    def latestTag = "${imageRef}:latest"
    def registryHost = cfg.registry.tokenize('/')[0]

    dir("apis/${cfg.apiPath}") {
        withCredentials([usernamePassword(
            credentialsId: cfg.registryCredentialsId,
            usernameVariable: 'REGISTRY_USER',
            passwordVariable: 'REGISTRY_PASSWORD'
        )]) {
            powershell """
                \$ErrorActionPreference = 'Stop'
                New-Item -ItemType Directory -Force -Path CompositeApps | Out-Null
                New-Item -ItemType Directory -Force -Path resources | Out-Null
                New-Item -ItemType Directory -Force -Path libs | Out-Null
                New-Item -ItemType File -Force -Path libs\\.dockerkeep | Out-Null
                Copy-Item -Path target\\*.car -Destination CompositeApps\\ -Force
                if (Test-Path target\\mi-runtime-libs) {
                  Copy-Item -Path target\\mi-runtime-libs\\*.jar -Destination libs\\ -Force -ErrorAction SilentlyContinue
                }
                if (Test-Path deployment\\docker\\resources) {
                  Copy-Item -Path deployment\\docker\\resources\\* -Destination resources\\ -Recurse -Force
                }

                \$env:REGISTRY_PASSWORD | docker login '${registryHost}' --username \$env:REGISTRY_USER --password-stdin
                docker build `
                  --label org.opencontainers.image.revision='${commitSha}' `
                  --label org.opencontainers.image.version='${cfg.version}' `
                  --label org.opencontainers.image.source='${sourceUrl ?: 'unknown'}' `
                  --build-arg BASE_IMAGE=wso2/wso2mi:4.6.0 `
                  --build-arg WSO2_SERVER_HOME=/home/wso2carbon/wso2mi-4.6.0 `
                  -f deployment/docker/Dockerfile `
                  -t '${imageTag}' .
                docker push '${imageTag}'
                ${pushLatest ? "docker tag '${imageTag}' '${latestTag}'\ndocker push '${latestTag}'" : ""}
            """
        }
    }

    return imageTag
}

def commandOutput(String command) {
    return powershell(script: command, returnStdout: true).trim()
}

def remoteUrl() {
    return powershell(
        script: '''
            $url = git config --get remote.origin.url
            if ($LASTEXITCODE -eq 0) {
              $url
            }
        ''',
        returnStdout: true
    ).trim()
}

return this
