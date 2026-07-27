def fs(String targetPath) {
    if (isUnix()) {
        sh """
            set -euo pipefail
            TRIVY_CACHE_DIR="\${TRIVY_UNIX_CACHE_DIR:-.trivy-cache}"
            mkdir -p "\$TRIVY_CACHE_DIR"

            if ! command -v gitleaks >/dev/null 2>&1; then
              echo "gitleaks is required for secret scanning but is not installed on this agent"
              exit 1
            fi

            if ! command -v trivy >/dev/null 2>&1; then
              echo "trivy is required for filesystem scanning but is not installed on this agent"
              exit 1
            fi

            gitleaks detect --source '${targetPath}' --redact --exit-code 1
            trivy fs --cache-dir "\$TRIVY_CACHE_DIR" --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret,misconfig '${targetPath}'
        """
        return
    }

    powershell """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = if (\$env:TRIVY_WINDOWS_CACHE_DIR) { \$env:TRIVY_WINDOWS_CACHE_DIR } else { 'C:\\trivy-cache' }
        New-Item -ItemType Directory -Force -Path \$trivyCacheDir | Out-Null

        if (-not (Get-Command gitleaks -ErrorAction SilentlyContinue)) {
          throw 'gitleaks is required for secret scanning but is not installed on this agent'
        }

        if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
          throw 'trivy is required for filesystem scanning but is not installed on this agent'
        }

        gitleaks detect --source '${targetPath}' --redact --exit-code 1
        trivy fs --cache-dir \$trivyCacheDir --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret,misconfig '${targetPath}'
    """
}

def image(String imageRef, String failSeverity = 'CRITICAL') {
    if (isUnix()) {
        sh """
            set -euo pipefail
            TRIVY_CACHE_DIR="\${TRIVY_UNIX_CACHE_DIR:-.trivy-cache}"
            mkdir -p "\$TRIVY_CACHE_DIR"

            if ! command -v trivy >/dev/null 2>&1; then
              echo "trivy is required for image scanning but is not installed on this agent"
              exit 1
            fi

            trivy image --cache-dir "\$TRIVY_CACHE_DIR" --scanners vuln --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
            trivy image --cache-dir "\$TRIVY_CACHE_DIR" --scanners vuln --exit-code 1 --severity '${failSeverity}' '${imageRef}'
        """
        return
    }

    powershell """
        \$ErrorActionPreference = 'Stop'
        \$trivyCacheDir = if (\$env:TRIVY_WINDOWS_CACHE_DIR) { \$env:TRIVY_WINDOWS_CACHE_DIR } else { 'C:\\trivy-cache' }
        New-Item -ItemType Directory -Force -Path \$trivyCacheDir | Out-Null

        if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
          throw 'trivy is required for image scanning but is not installed on this agent'
        }

        trivy image --cache-dir \$trivyCacheDir --scanners vuln --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
        trivy image --cache-dir \$trivyCacheDir --scanners vuln --exit-code 1 --severity '${failSeverity}' '${imageRef}'
    """
}

return this
