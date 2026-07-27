def call(Map cfg) {
    def shortSha = env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'unknown'
    def imageRef = "${cfg.registry}/${cfg.apiSlug}"
    def versionTag = cfg.version.startsWith('v') ? "${cfg.version}-${shortSha}" : "v${cfg.version}-${shortSha}"
    def imageTag = "${imageRef}:${versionTag}"
    def pushLatest = cfg.get('pushLatest', false)

    dir("apis/${cfg.apiPath}") {
        sh '''
            set -euo pipefail
            mkdir -p CompositeApps resources
            cp target/*.car CompositeApps/
            if [ -d deployment/docker/resources ]; then
              cp -R deployment/docker/resources/. resources/
            fi
        '''

        docker.withRegistry("https://${cfg.registry}", cfg.registryCredentialsId) {
            def labels = [
                "--label org.opencontainers.image.revision=${env.GIT_COMMIT ?: 'unknown'}",
                "--label org.opencontainers.image.version=${cfg.version}",
                "--label org.opencontainers.image.source=${env.GIT_URL ?: 'unknown'}"
            ].join(' ')
            def buildArgs = [
                "--build-arg BASE_IMAGE=wso2/wso2mi:4.6.0",
                "--build-arg WSO2_SERVER_HOME=/home/wso2carbon/wso2mi-4.6.0"
            ].join(' ')

            def img = docker.build(imageTag, "${labels} ${buildArgs} -f deployment/docker/Dockerfile .")
            img.push()

            if (pushLatest) {
                img.push('latest')
            }
        }
    }

    return imageTag
}

return this
