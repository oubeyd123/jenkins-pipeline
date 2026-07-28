def fs(String targetPath, String apiSlug = '') {
    def slug = apiSlug ?: targetPath.tokenize('/\\').takeRight(2).join('-')
    def reportDir = 'target/security-reports'
    def gitleaksJson = "${reportDir}/${slug}-gitleaks.tmp.json"
    def trivyFsJson = "${reportDir}/${slug}-trivy-fs.tmp.json"
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

            gitleaks detect --source '${targetPath}' --redact --report-format json --report-path '${gitleaksJson}' --exit-code 1
            if [ ! -f '${gitleaksJson}' ]; then
              printf '[]\\n' > '${gitleaksJson}'
            fi
            """,
            returnStatus: true
        )
        trivyStatus = sh(
            script: """
            set -euo pipefail
            TRIVY_CACHE_DIR="\${TRIVY_UNIX_CACHE_DIR:-.trivy-cache}"
            mkdir -p "\$TRIVY_CACHE_DIR" '${reportDir}'
            trivy fs --cache-dir "\$TRIVY_CACHE_DIR" --format json --output '${trivyFsJson}' --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret,misconfig '${targetPath}'
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

        gitleaks detect --source '${targetPath}' --redact --report-format json --report-path '${gitleaksJson}' --exit-code 1
        if (-not (Test-Path '${gitleaksJson}')) {
          '[]' | Set-Content -Path '${gitleaksJson}'
        }
        """,
            returnStatus: true
        )
        trivyStatus = powershell(
            script: """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = if (\$env:TRIVY_WINDOWS_CACHE_DIR) { \$env:TRIVY_WINDOWS_CACHE_DIR } else { 'C:\\trivy-cache' }
        New-Item -ItemType Directory -Force -Path \$trivyCacheDir | Out-Null
        New-Item -ItemType Directory -Force -Path '${reportDir}' | Out-Null
        trivy fs --cache-dir \$trivyCacheDir --format json --output '${trivyFsJson}' --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret,misconfig '${targetPath}'
        """,
            returnStatus: true
        )
    }

    def gitleaksFindings = countGitleaksFindings(gitleaksJson)
    def trivyFindings = countTrivyFindings(trivyFsJson)

    writeSecurityReport([
        apiSlug      : slug,
        scope        : 'filesystem',
        target       : targetPath,
        result       : (gitleaksStatus == 0 && trivyStatus == 0) ? 'PASSED' : 'FAILED',
        findings     : [
            [name: 'Secrets', count: gitleaksFindings],
            [name: 'Critical', count: trivyFindings.critical],
            [name: 'High', count: trivyFindings.high],
            [name: 'Policy-failing findings', count: gitleaksFindings + trivyFindings.critical + trivyFindings.high],
        ],
        checks       : [
            [tool: 'Gitleaks', scope: 'Source secrets', policy: 'No secrets allowed', result: statusText(gitleaksStatus)],
            [tool: 'Trivy FS', scope: 'Source/config', policy: 'HIGH/CRITICAL fail', result: statusText(trivyStatus)],
        ],
    ])
    archiveArtifacts allowEmptyArchive: true, artifacts: "${reportDir}/*.md"
    removeTemporaryReportFiles([gitleaksJson, trivyFsJson])

    if (gitleaksStatus != 0 || trivyStatus != 0) {
        error "Security filesystem scan failed for ${slug}"
    }
}

def image(String imageRef, String apiSlug = '', String failSeverity = 'CRITICAL') {
    def slug = apiSlug ?: imageRef.tokenize('/:').takeRight(2).join('-')
    def reportDir = 'target/security-reports'
    def trivyImageJson = "${reportDir}/${slug}-trivy-image.tmp.json"
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

            trivy image --cache-dir "\$TRIVY_CACHE_DIR" --scanners vuln --format json --output '${trivyImageJson}' --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
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

        trivy image --cache-dir \$trivyCacheDir --scanners vuln --format json --output '${trivyImageJson}' --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
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

    def trivyFindings = countTrivyFindings(trivyImageJson)

    writeSecurityReport([
        apiSlug      : slug,
        scope        : 'image',
        target       : imageRef,
        result       : (reportStatus == 0 && gateStatus == 0) ? 'PASSED' : 'FAILED',
        findings     : [
            [name: 'Critical', count: trivyFindings.critical],
            [name: 'High', count: trivyFindings.high],
            [name: 'Policy-failing findings', count: failSeverity.tokenize(',').collect { trivyFindings[it.trim().toLowerCase()] ?: 0 }.sum() ?: 0],
        ],
        checks       : [
            [tool: 'Trivy Image', scope: 'Docker image', policy: "${failSeverity} fail", result: statusText(gateStatus)],
        ],
    ])
    archiveArtifacts allowEmptyArchive: true, artifacts: "${reportDir}/*.md"
    removeTemporaryReportFiles([trivyImageJson])

    if (reportStatus != 0 || gateStatus != 0) {
        error "Security image scan failed for ${slug}"
    }
}

def writeSecurityReport(Map cfg) {
    def reportFile = "target/security-reports/${cfg.apiSlug}-${cfg.scope}-security-report.md"
    def rows = cfg.checks.collect { check ->
        "| ${check.tool} | ${check.scope} | ${check.policy} | ${check.result} |"
    }.join('\n')
    def findingRows = cfg.findings.collect { finding ->
        "| ${finding.name} | ${finding.count} |"
    }.join('\n')

    writeFile file: reportFile, text: """# Security Scan Report

| Field  |     Value      |
|--------|----------------|
| API    | ${cfg.apiSlug} |
| Branch | ${env.BRANCH_NAME ?: 'n/a'} |
| Commit | ${env.SOURCE_COMMIT ?: env.GIT_COMMIT ?: 'n/a'} |
| Scope  | ${cfg.scope}  |
| Target | ${cfg.target} |
| Result | ${cfg.result} |

## Findings

| Type | Count |
|------|------:|
${findingRows}

## Checks

| Tool | Scope | Policy | Result |
|------|-------|--------|--------|
${rows}
"""
}

def statusText(int status) {
    return status == 0 ? 'PASSED' : 'FAILED'
}

def countGitleaksFindings(String reportFile) {
    if (!fileExists(reportFile)) {
        return 0
    }

    def content = readFile(reportFile).trim()
    if (!content) {
        return 0
    }

    def parsed = new groovy.json.JsonSlurperClassic().parseText(content)
    return parsed instanceof List ? parsed.size() : 0
}

def countTrivyFindings(String reportFile) {
    def counts = [critical: 0, high: 0]
    if (!fileExists(reportFile)) {
        return counts
    }

    def content = readFile(reportFile).trim()
    if (!content) {
        return counts
    }

    def parsed = new groovy.json.JsonSlurperClassic().parseText(content)
    parsed.Results?.each { result ->
        result.Vulnerabilities?.each { finding ->
            addSeverity(counts, finding.Severity)
        }
        result.Misconfigurations?.each { finding ->
            addSeverity(counts, finding.Severity)
        }
        result.Secrets?.each { finding ->
            addSeverity(counts, finding.Severity)
        }
    }

    return counts
}

def addSeverity(Map counts, String severity) {
    switch ((severity ?: '').toLowerCase()) {
        case 'critical':
            counts.critical += 1
            break
        case 'high':
            counts.high += 1
            break
    }
}

def removeTemporaryReportFiles(List files) {
    files.each { file ->
        if (fileExists(file)) {
            if (isUnix()) {
                sh "rm -f '${file}'"
            } else {
                powershell "Remove-Item -LiteralPath '${file}' -Force"
            }
        }
    }
}

return this
