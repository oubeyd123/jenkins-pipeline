def call(Map cfg) {
    if (cfg.url) {
        powershell """
            \$ErrorActionPreference = 'Stop'
            curl.exe --fail --silent --show-error --retry 5 --retry-delay 5 '${cfg.url}'
        """
        return
    }

    if (!cfg.baseUrl) {
        echo "No smoke test URL configured for ${cfg.apiSlug}; skipping smoke test"
        return
    }

    if (cfg.contexts) {
        runSmokeContexts(cfg.apiSlug, cfg.baseUrl, cfg.contexts)
        return
    }

    if (!cfg.apiPath) {
        echo "No WSO2 API contexts configured for ${cfg.apiSlug}; skipping smoke test"
        return
    }

    powershell """
        \$ErrorActionPreference = 'Stop'

        \$apiDir = 'apis/${cfg.apiPath}/src/main/wso2mi/artifacts/apis'
        if (-not (Test-Path \$apiDir)) {
          Write-Host "No WSO2 API artifact directory found at \$apiDir; skipping smoke test"
          return
        }

        \$contexts = Get-ChildItem -Path \$apiDir -Filter '*.xml' -Recurse |
          ForEach-Object {
            [xml]\$doc = Get-Content -LiteralPath \$_.FullName
            \$context = \$doc.api.context
            foreach (\$resource in \$doc.api.resource) {
              \$uri = \$resource.'uri-template'
              if ([string]::IsNullOrWhiteSpace(\$uri) -or \$uri -eq '/') {
                \$path = \$context
              } else {
                \$path = \$context + \$uri
              }

              foreach (\$method in (\$resource.methods -split '\\s+')) {
                if (-not [string]::IsNullOrWhiteSpace(\$method)) {
                  \$method.ToUpperInvariant() + '|' + \$path
                }
              }
            }
          } |
          Where-Object { -not [string]::IsNullOrWhiteSpace(\$_) } |
          Sort-Object -Unique

        if (-not \$contexts) {
          Write-Host "No WSO2 API contexts found under \$apiDir; skipping smoke test"
          return
        }

        \$contextsText = \$contexts -join "`n"
        \$contextsText | Set-Content -Path smoke-contexts.txt
    """

    def contexts = readFile('smoke-contexts.txt').trim()
    runSmokeContexts(cfg.apiSlug, cfg.baseUrl, contexts)
}

def runSmokeContexts(String apiSlug, String baseUrl, String contexts) {
    powershell """
        \$ErrorActionPreference = 'Stop'
        \$contexts = @'
${contexts}
'@ -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace(\$_) }
        \$smokeBody = '{"currency":"EUR","amount":1,"customerId":"smoke-test","name":"Smoke Test"}'

        foreach (\$target in \$contexts) {
          \$parts = \$target -split '\\|', 2
          if (\$parts.Count -eq 2) {
            \$method = \$parts[0].Trim().ToUpperInvariant()
            \$context = \$parts[1].Trim()
          } else {
            \$method = 'GET'
            \$context = \$target.Trim()
          }

          \$url = '${baseUrl}' + \$context
          \$passed = \$false

          for (\$attempt = 1; \$attempt -le 24; \$attempt++) {
            if (\$method -in @('POST', 'PUT', 'PATCH')) {
              \$status = curl.exe -X \$method --silent --show-error --output NUL --write-out '%{http_code}' -H 'Content-Type: application/json' -d \$smokeBody "\$url"
            } else {
              \$status = curl.exe -X \$method --silent --show-error --output NUL --write-out '%{http_code}' "\$url"
            }
            if (\$LASTEXITCODE -ne 0) {
              \$status = '000'
            }

            Write-Host "Smoke testing \$method \$url returned HTTP \$status (attempt \$attempt/24)"
            if (\$status -match '^[23]') {
              \$passed = \$true
              break
            }

            if (\$passed) {
              break
            }

            Start-Sleep -Seconds 5
          }

          if (-not \$passed) {
            docker logs --tail 200 '${apiSlug}'
            throw "Smoke test failed for \$method \$context"
          }
        }
    """
}

return this
