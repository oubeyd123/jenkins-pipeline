def call(Map cfg) {
    def commitSha = cfg.get('commitSha', env.SOURCE_COMMIT ?: env.GIT_COMMIT ?: commandOutput('git rev-parse HEAD'))
    def shortSha = commitSha.take(8)
    def sourceUrl = cfg.get('sourceUrl', env.SOURCE_URL ?: env.GIT_URL ?: remoteUrl())
    def imageName = cfg.get('imageName', 'wso2-mi-dev')
    def imageRef = "${cfg.registry}/${imageName}"
    def imageTag = "${imageRef}:${cfg.version}-${shortSha}"
    def registryHost = cfg.registry.tokenize('/')[0]

    withCredentials([usernamePassword(
        credentialsId: cfg.registryCredentialsId,
        usernameVariable: 'REGISTRY_USER',
        passwordVariable: 'REGISTRY_PASSWORD'
    )]) {
        powershell """
            \$ErrorActionPreference = 'Stop'
            \$env:REGISTRY_PASSWORD | docker login '${registryHost}' --username \$env:REGISTRY_USER --password-stdin
            docker build `
              --label org.opencontainers.image.revision='${commitSha}' `
              --label org.opencontainers.image.version='${cfg.version}' `
              --label org.opencontainers.image.source='${sourceUrl ?: 'unknown'}' `
              -f Dockerfile.dev `
              -t '${imageTag}' .
            docker push '${imageTag}'
        """
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
