def fs(String targetPath, String apiSlug = '') {
    def slug = apiSlug ?: targetPath.tokenize('/\\').takeRight(2).join('-')
    def reportDir = 'target/security-reports'
    int gitleaksStatus
    int trivyStatus

    if (isUnix()) {
        gitleaksStatus = sh(
            script: """
            set -euo pipefail
            TRIVY_CACHE_DIR="\${TRIVY_UNIX_CACHE_DIR:-.trivy-cache}"
            mkdir -p "\$TRIVY_CACHE_DIR" '${reportDir}'

            if ! command -v gitleaks >/dev/null 2>&1; then
              echo "gitleaks is required for secret scanning but is not installed on this agent"
              exit 1
            fi

            if ! command -v trivy >/dev/null 2>&1; then
              echo "trivy is required for filesystem scanning but is not installed on this agent"
              exit 1
            fi

            gitleaks detect --source '${targetPath}' --redact --exit-code 1
            """,
            returnStatus: true
        )
        trivyStatus = sh(
            script: """
            set -euo pipefail
            TRIVY_CACHE_DIR="\${TRIVY_UNIX_CACHE_DIR:-.trivy-cache}"
            mkdir -p "\$TRIVY_CACHE_DIR" '${reportDir}'
            trivy fs --cache-dir "\$TRIVY_CACHE_DIR" --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret,misconfig '${targetPath}'
            """,
            returnStatus: true
        )
    } else {
        gitleaksStatus = powershell(
            script: """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = if (\$env:TRIVY_WINDOWS_CACHE_DIR) { \$env:TRIVY_WINDOWS_CACHE_DIR } else { 'C:\\trivy-cache' }
        New-Item -ItemType Directory -Force -Path \$trivyCacheDir | Out-Null
        New-Item -ItemType Directory -Force -Path '${reportDir}' | Out-Null

        if (-not (Get-Command gitleaks -ErrorAction SilentlyContinue)) {
          throw 'gitleaks is required for secret scanning but is not installed on this agent'
        }

        if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
          throw 'trivy is required for filesystem scanning but is not installed on this agent'
        }

        gitleaks detect --source '${targetPath}' --redact --exit-code 1
        """,
            returnStatus: true
        )
        trivyStatus = powershell(
            script: """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = if (\$env:TRIVY_WINDOWS_CACHE_DIR) { \$env:TRIVY_WINDOWS_CACHE_DIR } else { 'C:\\trivy-cache' }
        New-Item -ItemType Directory -Force -Path \$trivyCacheDir | Out-Null
        New-Item -ItemType Directory -Force -Path '${reportDir}' | Out-Null
        trivy fs --cache-dir \$trivyCacheDir --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret,misconfig '${targetPath}'
        """,
            returnStatus: true
        )
    }

    writeSecurityReport([
        apiSlug      : slug,
        scope        : 'filesystem',
        target       : targetPath,
        result       : (gitleaksStatus == 0 && trivyStatus == 0) ? 'PASSED' : 'FAILED',
        checks       : [
            [tool: 'Gitleaks', scope: 'Source secrets', policy: 'No secrets allowed', result: statusText(gitleaksStatus)],
            [tool: 'Trivy FS', scope: 'Source/config', policy: 'HIGH/CRITICAL fail', result: statusText(trivyStatus)],
        ],
    ])
    archiveArtifacts allowEmptyArchive: true, artifacts: "${reportDir}/*.md"

    if (gitleaksStatus != 0 || trivyStatus != 0) {
        error "Security filesystem scan failed for ${slug}"
    }
}

def image(String imageRef, String apiSlug = '', String failSeverity = 'CRITICAL') {
    def slug = apiSlug ?: imageRef.tokenize('/:').takeRight(2).join('-')
    def reportDir = 'target/security-reports'
    int reportStatus
    int gateStatus

    if (isUnix()) {
        reportStatus = sh(
            script: """
            set -euo pipefail
            TRIVY_CACHE_DIR="\${TRIVY_UNIX_CACHE_DIR:-.trivy-cache}"
            mkdir -p "\$TRIVY_CACHE_DIR" '${reportDir}'

            if ! command -v trivy >/dev/null 2>&1; then
              echo "trivy is required for image scanning but is not installed on this agent"
              exit 1
            fi

            trivy image --cache-dir "\$TRIVY_CACHE_DIR" --scanners vuln --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
            """,
            returnStatus: true
        )
        gateStatus = sh(
            script: """
            set -euo pipefail
            TRIVY_CACHE_DIR="\${TRIVY_UNIX_CACHE_DIR:-.trivy-cache}"
            trivy image --cache-dir "\$TRIVY_CACHE_DIR" --scanners vuln --exit-code 1 --severity '${failSeverity}' '${imageRef}'
            """,
            returnStatus: true
        )
    } else {
        reportStatus = powershell(
            script: """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = if (\$env:TRIVY_WINDOWS_CACHE_DIR) { \$env:TRIVY_WINDOWS_CACHE_DIR } else { 'C:\\trivy-cache' }
        New-Item -ItemType Directory -Force -Path \$trivyCacheDir | Out-Null
        New-Item -ItemType Directory -Force -Path '${reportDir}' | Out-Null

        if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
          throw 'trivy is required for image scanning but is not installed on this agent'
        }

        trivy image --cache-dir \$trivyCacheDir --scanners vuln --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
        """,
            returnStatus: true
        )
        gateStatus = powershell(
            script: """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = if (\$env:TRIVY_WINDOWS_CACHE_DIR) { \$env:TRIVY_WINDOWS_CACHE_DIR } else { 'C:\\trivy-cache' }
        trivy image --cache-dir \$trivyCacheDir --scanners vuln --exit-code 1 --severity '${failSeverity}' '${imageRef}'
        """,
            returnStatus: true
        )
    }

    writeSecurityReport([
        apiSlug      : slug,
        scope        : 'image',
        target       : imageRef,
        result       : (reportStatus == 0 && gateStatus == 0) ? 'PASSED' : 'FAILED',
        checks       : [
            [tool: 'Trivy Image', scope: 'Docker image', policy: "${failSeverity} fail", result: statusText(gateStatus)],
        ],
    ])
    archiveArtifacts allowEmptyArchive: true, artifacts: "${reportDir}/*.md"

    if (reportStatus != 0 || gateStatus != 0) {
        error "Security image scan failed for ${slug}"
    }
}

def writeSecurityReport(Map cfg) {
    def reportFile = "target/security-reports/${cfg.apiSlug}-${cfg.scope}-security-report.md"
    def rows = cfg.checks.collect { check ->
        "| ${check.tool} | ${check.scope} | ${check.policy} | ${check.result} |"
    }.join('\n')

    writeFile file: reportFile, text: """# Security Scan Report

| Field | Value |
|---|---|
| API | ${cfg.apiSlug} |
| Branch | ${env.BRANCH_NAME ?: 'n/a'} |
| Commit | ${env.SOURCE_COMMIT ?: env.GIT_COMMIT ?: 'n/a'} |
| Scope | ${cfg.scope} |
| Target | ${cfg.target} |
| Result | ${cfg.result} |

## Checks

| Tool | Scope | Policy | Result |
|---|---|---|---|
${rows}
"""
}

def statusText(int status) {
    return status == 0 ? 'PASSED' : 'FAILED'
}

return this
