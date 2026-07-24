def call(String apiPath) {
    dir("apis/${apiPath}") {
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
