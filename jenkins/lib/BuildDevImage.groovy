def call(Map cfg) {
    def shortSha = env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'unknown'
    def imageName = cfg.get('imageName', 'wso2-mi-dev')
    def imageRef = "${cfg.registry}/${imageName}"
    def imageTag = "${imageRef}:${cfg.version}-${shortSha}"
    def registryHost = cfg.registry.tokenize('/')[0]

    withCredentials([usernamePassword(
        credentialsId: cfg.registryCredentialsId,
        usernameVariable: 'REGISTRY_USER',
        passwordVariable: 'REGISTRY_PASSWORD'
    )]) {
        if (isUnix()) {
            sh """
                set -euo pipefail
                echo "\$REGISTRY_PASSWORD" | docker login '${registryHost}' --username "\$REGISTRY_USER" --password-stdin
                docker build \
                  --label org.opencontainers.image.revision='${env.GIT_COMMIT ?: 'unknown'}' \
                  --label org.opencontainers.image.version='${cfg.version}' \
                  --label org.opencontainers.image.source='${env.GIT_URL ?: 'unknown'}' \
                  -f Dockerfile.dev \
                  -t '${imageTag}' .
                docker push '${imageTag}'
            """
        } else {
            powershell """
                \$ErrorActionPreference = 'Stop'
                \$env:REGISTRY_PASSWORD | docker login '${registryHost}' --username \$env:REGISTRY_USER --password-stdin
                docker build `
                  --label org.opencontainers.image.revision='${env.GIT_COMMIT ?: 'unknown'}' `
                  --label org.opencontainers.image.version='${cfg.version}' `
                  --label org.opencontainers.image.source='${env.GIT_URL ?: 'unknown'}' `
                  -f Dockerfile.dev `
                  -t '${imageTag}' .
                docker push '${imageTag}'
            """
        }
    }


    return imageTag
}

return this
