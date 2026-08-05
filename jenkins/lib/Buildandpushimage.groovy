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
    def baseImage = cfg.get('baseImage', env.WSO2_BASE_IMAGE ?: 'wso2/wso2mi:4.6.0')
    def serverHome = cfg.get('serverHome', env.WSO2_SERVER_HOME ?: '/home/wso2carbon/wso2mi-4.6.0')

    dir("apis/${cfg.apiPath}") {
        echo "Docker registry login: registry=${registryHost}, credential=${cfg.registryCredentialsId}"
        withCredentials([string(credentialsId: cfg.registryCredentialsId, variable: 'DOCKERHUB_TOKEN')]) {
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

                \$dockerUser = 'oubeyd'
                \$dockerPassword = \$env:DOCKERHUB_TOKEN.Trim()
                Write-Host "Docker login user: \$dockerUser"
                \$dockerPassword | docker login '${registryHost}' --username \$dockerUser --password-stdin
                if (\$LASTEXITCODE -ne 0) {
                  throw 'Docker login failed'
                }

                Invoke-Native docker build `
                  --label org.opencontainers.image.revision='${commitSha}' `
                  --label org.opencontainers.image.version='${cfg.version}' `
                  --label org.opencontainers.image.source='${sourceUrl ?: 'unknown'}' `
                  --build-arg BASE_IMAGE='${baseImage}' `
                  --build-arg WSO2_SERVER_HOME='${serverHome}' `
                  -f deployment/docker/Dockerfile `
                  -t '${imageTag}' .
                Invoke-Native docker push '${imageTag}'
                ${pushLatest ? "Invoke-Native docker tag '${imageTag}' '${latestTag}'\nInvoke-Native docker push '${latestTag}'" : ""}
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
