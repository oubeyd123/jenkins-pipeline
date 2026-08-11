def call(Map cfg) {
    if ((env.SONAR_ENABLED ?: 'false').toBoolean() != true) {
        echo "SonarQube disabled; skipping scan for ${cfg.apiSlug}"
        return
    }

    if (!(env.SONAR_HOST_URL ?: '').trim()) {
        error 'SONAR_HOST_URL is required when SONAR_ENABLED=true'
    }

    if (!(env.SONAR_TOKEN_CRED_ID ?: '').trim()) {
        error 'SONAR_TOKEN_CRED_ID is required when SONAR_ENABLED=true'
    }

    def apiPath = "apis/${cfg.apiPath}"
    def projectKey = "wso2-mi-${cfg.apiSlug}"
    def qualityGateMode = (env.SONAR_QUALITY_GATE ?: 'report').trim().toLowerCase()
    def waitForGate = qualityGateMode == 'enforce'

    dir(apiPath) {
        withCredentials([string(credentialsId: env.SONAR_TOKEN_CRED_ID, variable: 'SONAR_TOKEN')]) {
            sh """
                set -euo pipefail

                if ! command -v sonar-scanner >/dev/null 2>&1; then
                  echo "sonar-scanner is not installed on this Jenkins agent"
                  exit 1
                fi

                sonar-scanner \
                  -Dsonar.host.url='${env.SONAR_HOST_URL}' \
                  -Dsonar.token="\$SONAR_TOKEN" \
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
