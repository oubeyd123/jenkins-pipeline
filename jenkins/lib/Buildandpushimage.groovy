def call(Map cfg) {
    def shortSha = env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'unknown'
    def imageRef = "${cfg.registry}/${cfg.apiSlug}"
    def versionTag = cfg.version.startsWith('v') ? "${cfg.version}-${shortSha}" : "v${cfg.version}-${shortSha}"
    def imageTag = "${imageRef}:${versionTag}"
    def pushLatest = cfg.get('pushLatest', false)

    dir("apis/${cfg.apiPath}") {
        docker.withRegistry("https://${cfg.registry}", cfg.registryCredentialsId) {
            def labels = [
                "--label org.opencontainers.image.revision=${env.GIT_COMMIT ?: 'unknown'}",
                "--label org.opencontainers.image.version=${cfg.version}",
                "--label org.opencontainers.image.source=${env.GIT_URL ?: 'unknown'}"
            ].join(' ')

            def img = docker.build(imageTag, "${labels} -f deployment/docker/Dockerfile .")
            img.push()

            if (pushLatest) {
                img.push('latest')
            }
        }
    }

    return imageTag
}

return this
