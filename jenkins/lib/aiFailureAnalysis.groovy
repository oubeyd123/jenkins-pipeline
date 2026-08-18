def call() {
    def reportDir = 'target/ai-failure'
    def logFile = "${reportDir}/jenkins-console.log"
    def extractedFile = "${reportDir}/extracted-errors.json"

    sh "mkdir -p '${reportDir}'"

    try {
        writeFile file: logFile, text: currentBuild.rawBuild.getLog(20000).join('\n')
    } catch (err) {
        writeFile file: logFile, text: "Could not read Jenkins console log from currentBuild.rawBuild: ${err.message}\n"
    }

    sh """
        set -euo pipefail
        python3 tools/ai-log-analyzer/extract_errors.py \
          --input '${logFile}' \
          --output '${extractedFile}' \
          --pipeline '${env.JOB_NAME ?: ''}' \
          --build-number '${env.BUILD_NUMBER ?: ''}' \
          --branch '${env.BRANCH_NAME ?: ''}' \
          --commit '${env.GIT_COMMIT ?: env.SOURCE_COMMIT ?: ''}' \
          --status 'FAILED'
    """

    archiveArtifacts allowEmptyArchive: true, artifacts: "${reportDir}/*.json"

    if ((env.AI_ANALYZER_URL ?: '').trim()) {
        sh """
            set -euo pipefail
            curl --silent --show-error --retry 3 --retry-delay 2 --retry-connrefused \
              --connect-timeout 10 --max-time 60 \
              --header 'Content-Type: application/json' \
              --data @'${extractedFile}' \
              '${env.AI_ANALYZER_URL}/api/analyze' \
              --output '${reportDir}/analysis-response.json'
        """
        archiveArtifacts allowEmptyArchive: true, artifacts: "${reportDir}/analysis-response.json"
    } else {
        echo 'AI_ANALYZER_URL is not configured; extracted failure JSON was archived only'
    }
}

return this
