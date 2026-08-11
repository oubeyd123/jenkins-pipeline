def call(Map cfg) {
    if ((env.SONAR_ENABLED ?: 'false').toBoolean() != true) {
        echo "SonarQube disabled; skipping scan for ${cfg.apiSlug}"
        return
    }

    def apiPath = "apis/${cfg.apiPath}"
    def projectKey = "wso2-mi-${cfg.apiSlug}"
    def scannerTool = env.SONAR_SCANNER_TOOL ?: 'SonarScanner'
    def sonarServer = env.SONAR_SERVER_NAME ?: 'SonarQube'
    def qualityGateMode = (env.SONAR_QUALITY_GATE ?: 'report').trim().toLowerCase()
    def waitForGate = qualityGateMode == 'enforce'

    dir(apiPath) {
        def scannerHome = tool scannerTool
        withSonarQubeEnv(sonarServer) {
            sh """
                set -euo pipefail

                '${scannerHome}/bin/sonar-scanner' \
                  -Dsonar.projectKey='${projectKey}' \
                  -Dsonar.projectName='${cfg.apiSlug}' \
                  -Dsonar.projectBaseDir='.' \
                  -Dsonar.sources='src/main/wso2mi' \
                  -Dsonar.sourceEncoding='UTF-8' \
                  -Dsonar.qualitygate.wait='${waitForGate}'
            """
        }
    }
}

return this
