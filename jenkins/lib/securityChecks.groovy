def fs(String targetPath, String apiSlug = '') {
    def slug = apiSlug ?: targetPath.tokenize('/\\').takeRight(2).join('-')
    def reportDir = 'target/security-reports'
    def gitleaksJson = "${reportDir}/${slug}-gitleaks.tmp.json"
    def gitleaksLog = "${reportDir}/${slug}-gitleaks.log"
    def trivyFsJson = "${reportDir}/${slug}-trivy-fs.tmp.json"
    def trivyFsLog = "${reportDir}/${slug}-trivy-fs.log"
    int gitleaksStatus
    int trivyStatus

    gitleaksStatus = sh(
        script: """
        set -euo pipefail
        TRIVY_CACHE_DIR="\${TRIVY_FS_CACHE_DIR}"
        mkdir -p "\$TRIVY_CACHE_DIR" '${reportDir}'

        if ! command -v gitleaks >/dev/null 2>&1; then
          echo "gitleaks is required for secret scanning but is not installed on the Linux build agent"
          exit 1
        fi

        if ! command -v trivy >/dev/null 2>&1; then
          echo "trivy is required for filesystem scanning but is not installed on the Linux build agent"
          exit 1
        fi

        set +e
        gitleaks dir '${targetPath}' --redact --report-format json --report-path '${gitleaksJson}' --exit-code 1 > '${gitleaksLog}' 2>&1
        status=\$?
        set -e
        if [ ! -f '${gitleaksJson}' ]; then
          printf '[]\\n' > '${gitleaksJson}'
        }
        exit \$status
        """,
        returnStatus: true
    )
    trivyStatus = sh(
        script: """
        set -euo pipefail
        TRIVY_CACHE_DIR="\${TRIVY_FS_CACHE_DIR}"
        mkdir -p "\$TRIVY_CACHE_DIR" '${reportDir}'

        set +e
        trivy fs --cache-dir "\$TRIVY_CACHE_DIR" --format json --output '${trivyFsJson}' --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,misconfig '${targetPath}' > '${trivyFsLog}' 2>&1
        status=\$?
        set -e
        exit \$status
        """,
        returnStatus: true
    )

    def gitleaksFindings = countGitleaksFindings(gitleaksJson)
    def trivyFindings = countTrivyFindings(trivyFsJson)
    def policyFailed = gitleaksFindings > 0 || trivyFindings.critical > 0 || trivyFindings.high > 0
    def gitleaksCleanButNonZero = gitleaksStatus != 0 && gitleaksFindings == 0 && fileContains(gitleaksLog, 'no leaks found')
    def toolFailed = (gitleaksStatus != 0 && !gitleaksCleanButNonZero) || (trivyStatus != 0 && trivyFindings.critical + trivyFindings.high == 0)

    writeSecurityReport([
        apiSlug      : slug,
        scope        : 'filesystem',
        target       : targetPath,
        result       : (policyFailed || toolFailed) ? 'FAILED' : 'PASSED',
        findings     : [
            [name: 'Secrets', count: gitleaksFindings],
            [name: 'Critical', count: trivyFindings.critical],
            [name: 'High', count: trivyFindings.high],
            [name: 'Policy-failing findings', count: gitleaksFindings + trivyFindings.critical + trivyFindings.high],
        ],
        checks       : [
            [tool: 'Gitleaks', scope: 'Source secrets', policy: 'No secrets allowed', result: statusText(gitleaksStatus)],
            [tool: 'Trivy FS', scope: 'Source vulnerabilities/misconfigurations', policy: 'HIGH/CRITICAL fail', result: statusText(trivyStatus)],
        ],
    ])
    archiveArtifacts allowEmptyArchive: true, artifacts: "${reportDir}/*.md,${reportDir}/*.log"
    sh "rm -f '${gitleaksJson}' '${trivyFsJson}'"

    if (policyFailed || toolFailed) {
        error "Security filesystem scan failed for ${slug}: Gitleaks=${statusText(gitleaksStatus)}, Trivy FS=${statusText(trivyStatus)}, Secrets=${gitleaksFindings}, Critical=${trivyFindings.critical}, High=${trivyFindings.high}. Check archived files ${reportDir}/${slug}-filesystem-security-report.md and ${reportDir}/${slug}-gitleaks.log"
    }
}

def image(String imageRef, String apiSlug = '', String failSeverity = 'CRITICAL') {
    def slug = apiSlug ?: imageRef.tokenize('/:').takeRight(2).join('-')
    def reportDir = 'target/security-reports'
    def trivyImageJson = "${reportDir}/${slug}-trivy-image.tmp.json"
    int reportStatus
    int gateStatus

    reportStatus = powershell(
        script: """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = \$env:TRIVY_IMAGE_CACHE_DIR
        New-Item -ItemType Directory -Force -Path \$trivyCacheDir | Out-Null
        New-Item -ItemType Directory -Force -Path '${reportDir}' | Out-Null

        if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
          throw 'trivy is required for image scanning but is not installed on the Windows Docker agent'
        }

        trivy image --cache-dir \$trivyCacheDir --scanners vuln --format json --output '${trivyImageJson}' --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
        """,
        returnStatus: true
    )
    gateStatus = powershell(
        script: """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = \$env:TRIVY_IMAGE_CACHE_DIR
        trivy image --cache-dir \$trivyCacheDir --scanners vuln --exit-code 1 --severity '${failSeverity}' '${imageRef}'
        """,
        returnStatus: true
    )

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
    powershell "Remove-Item -LiteralPath '${trivyImageJson}' -Force"

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

    return countLiteral(content, '"RuleID"')
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

    def compact = content.replaceAll('\\s+', '')
    counts.critical = countLiteral(compact, '"Severity":"CRITICAL"')
    counts.high = countLiteral(compact, '"Severity":"HIGH"')

    return counts
}

def countLiteral(String content, String needle) {
    int count = 0
    int index = content.indexOf(needle)
    while (index >= 0) {
        count++
        index = content.indexOf(needle, index + needle.length())
    }
    return count
}

def fileContains(String file, String needle) {
    return fileExists(file) && readFile(file).contains(needle)
}

return this
