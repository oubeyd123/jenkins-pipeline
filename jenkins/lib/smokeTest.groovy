def call(Map cfg) {
    if (cfg.url) {
        sh """
            set -euo pipefail
            curl --fail --silent --show-error --retry 5 --retry-delay 5 '${cfg.url}'
        """
        return
    }

    if (!cfg.baseUrl) {
        echo "No smoke test URL configured for ${cfg.apiSlug}; skipping smoke test"
        return
    }

    if (cfg.contexts) {
        if (isUnix()) {
            sh """
                set -euo pipefail

                contexts='''${cfg.contexts}'''
                for context in \$contexts; do
                  url="${cfg.baseUrl}\$context"
                  slash_url="\$url/"
                  echo "Smoke testing \$url"
                  if curl --fail --silent --show-error --retry 5 --retry-delay 5 "\$url"; then
                    continue
                  fi

                  echo "Smoke testing \$slash_url"
                  curl --fail --silent --show-error --retry 5 --retry-delay 5 "\$slash_url"
                done
            """
        } else {
            powershell """
                \$ErrorActionPreference = 'Stop'
                \$contexts = @'
${cfg.contexts}
'@ -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace(\$_) }

                foreach (\$context in \$contexts) {
                  \$url = '${cfg.baseUrl}' + \$context
                  \$slashUrl = \$url + '/'
                  Write-Host "Smoke testing \$url"
                  curl.exe --fail --silent --show-error --retry 5 --retry-delay 5 "\$url"
                  if (\$LASTEXITCODE -eq 0) {
                    continue
                  }

                  Write-Host "Smoke testing \$slashUrl"
                  curl.exe --fail --silent --show-error --retry 5 --retry-delay 5 "\$slashUrl"
                  if (\$LASTEXITCODE -ne 0) {
                    exit \$LASTEXITCODE
                  }
                }
            """
        }
        return
    }

    if (!cfg.apiPath) {
        echo "No WSO2 API contexts configured for ${cfg.apiSlug}; skipping smoke test"
        return
    }

    sh """
        set -euo pipefail

        api_dir='apis/${cfg.apiPath}/src/main/wso2mi/artifacts/apis'
        if [ ! -d "\$api_dir" ]; then
          echo "No WSO2 API artifact directory found at \$api_dir; skipping smoke test"
          exit 0
        fi

        contexts=\$(find "\$api_dir" -name '*.xml' -print0 |
          xargs -0 -r sed -n 's/.*<api[^>]* context="\\([^"]*\\)".*/\\1/p' |
          sort -u)

        if [ -z "\$contexts" ]; then
          echo "No WSO2 API contexts found under \$api_dir; skipping smoke test"
          exit 0
        fi

        for context in \$contexts; do
          url="${cfg.baseUrl}\$context"
          slash_url="\$url/"
          echo "Smoke testing \$url"
          if curl --fail --silent --show-error --retry 5 --retry-delay 5 "\$url"; then
            continue
          fi

          echo "Smoke testing \$slash_url"
          curl --fail --silent --show-error --retry 5 --retry-delay 5 "\$slash_url"
        done
    """
}

return this
