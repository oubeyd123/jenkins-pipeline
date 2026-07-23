def call(Map cfg) {
    def shortSha = env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'unknown'
    def imageRef = "${cfg.registry}/${cfg.apiSlug}"
    def versionTag = "v${cfg.version}-${shortSha}"
 
    dir("apis/${cfg.apiPath}") {
        docker.withRegistry("https://${cfg.registry}", cfg.registryCredentialsId) {
            def img = docker.build("${imageRef}:${versionTag}", "-f deployment/docker/Dockerfile .")
            img.push()
            img.push('latest')
        }
    }
 
    return "${imageRef}:${versionTag}"
}
 
return this
