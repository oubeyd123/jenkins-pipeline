def fs(String targetPath) {
    if (isUnix()) {
        sh """
            set -euo pipefail

            if ! command -v gitleaks >/dev/null 2>&1; then
              echo "gitleaks is required for secret scanning but is not installed on this agent"
              exit 1
            fi

            if ! command -v trivy >/dev/null 2>&1; then
              echo "trivy is required for filesystem scanning but is not installed on this agent"
              exit 1
            fi

            gitleaks detect --source '${targetPath}' --redact --exit-code 1
            trivy fs --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret,misconfig '${targetPath}'
        """
        return
    }

    powershell """
        \$ErrorActionPreference = 'Stop'

        if (-not (Get-Command gitleaks -ErrorAction SilentlyContinue)) {
          throw 'gitleaks is required for secret scanning but is not installed on this agent'
        }

        if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
          throw 'trivy is required for filesystem scanning but is not installed on this agent'
        }

        gitleaks detect --source '${targetPath}' --redact --exit-code 1
        trivy fs --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret,misconfig '${targetPath}'
    """
}

def image(String imageRef) {
    if (isUnix()) {
        sh """
            set -euo pipefail

            if ! command -v trivy >/dev/null 2>&1; then
              echo "trivy is required for image scanning but is not installed on this agent"
              exit 1
            fi

            trivy image --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
            trivy image --exit-code 1 --severity CRITICAL '${imageRef}'
        """
        return
    }

    powershell """
        \$ErrorActionPreference = 'Stop'

        if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
          throw 'trivy is required for image scanning but is not installed on this agent'
        }

        trivy image --exit-code 0 --severity HIGH,CRITICAL '${imageRef}'
        trivy image --exit-code 1 --severity CRITICAL '${imageRef}'
    """
}

return this
