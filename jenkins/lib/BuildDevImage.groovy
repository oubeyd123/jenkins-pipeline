def call(Map cfg) {
    def shortSha = env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'unknown'
    def imageName = cfg.get('imageName', 'wso2-mi-dev')
    def imageRef = "${cfg.registry}/${imageName}"
    def imageTag = "${imageRef}:${cfg.version}-${shortSha}"

    docker.withRegistry("https://${cfg.registry}", cfg.registryCredentialsId) {
        def labels = [
            "--label org.opencontainers.image.revision=${env.GIT_COMMIT ?: 'unknown'}",
            "--label org.opencontainers.image.version=${cfg.version}",
            "--label org.opencontainers.image.source=${env.GIT_URL ?: 'unknown'}"
        ].join(' ')

        def img = docker.build(imageTag, "${labels} -f Dockerfile.dev .")
        img.push()
    }

    return imageTag
}

return this
