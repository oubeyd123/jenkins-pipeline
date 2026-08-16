def call(String apiPath) {
    return call(apiPath: apiPath)
}

def call(Map cfg) {
    def apiPath = cfg.apiPath
    def version = cfg.get('version', '')

    dir("apis/${apiPath}") {
        if (version?.trim()) {
            sh "mvn -B versions:set -DnewVersion='${version}' -DgenerateBackupPoms=false"
        }
        sh 'mvn -B clean verify'
    }

    def car = sh(
        script: "find apis/${apiPath}/target -name '*.car' | head -n1",
        returnStdout: true
    ).trim()

    if (!car) {
        error "No .car file was produced for apis/${apiPath}"
    }

    return car
}

return this
