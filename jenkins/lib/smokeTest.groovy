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
                  passed=false

                  for attempt in \$(seq 1 24); do
                    for candidate in "\$url" "\$slash_url"; do
                      status=\$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' "\$candidate" || true)
                      echo "Smoke testing \$candidate returned HTTP \$status (attempt \$attempt/24)"
                      case "\$status" in
                        2*|3*) passed=true; break 2 ;;
                      esac
                    done

                    sleep 5
                  done

                  if [ "\$passed" != "true" ]; then
                    echo "Smoke test failed for context \$context"
                    docker logs --tail 200 '${cfg.apiSlug}' || true
                    exit 1
                  fi
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
                  \$passed = \$false

                  for (\$attempt = 1; \$attempt -le 24; \$attempt++) {
                    foreach (\$candidate in @(\$url, \$slashUrl)) {
                      \$status = curl.exe --silent --show-error --output NUL --write-out '%{http_code}' "\$candidate"
                      if (\$LASTEXITCODE -ne 0) {
                        \$status = '000'
                      }

                      Write-Host "Smoke testing \$candidate returned HTTP \$status (attempt \$attempt/24)"
                      if (\$status -match '^[23]') {
                        \$passed = \$true
                        break
                      }
                    }

                    if (\$passed) {
                      break
                    }

                    Start-Sleep -Seconds 5
                  }

                  if (-not \$passed) {
                    docker logs --tail 200 '${cfg.apiSlug}'
                    throw "Smoke test failed for context \$context"
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
          passed=false

          for attempt in \$(seq 1 24); do
            for candidate in "\$url" "\$slash_url"; do
              status=\$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' "\$candidate" || true)
              echo "Smoke testing \$candidate returned HTTP \$status (attempt \$attempt/24)"
              case "\$status" in
                2*|3*) passed=true; break 2 ;;
              esac
            done

            sleep 5
          done

          if [ "\$passed" != "true" ]; then
            echo "Smoke test failed for context \$context"
            docker logs --tail 200 '${cfg.apiSlug}' || true
            exit 1
          fi
        done
    """
}

return this
