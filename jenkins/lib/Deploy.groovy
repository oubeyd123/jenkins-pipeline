def call(Map cfg) {
    def containerName = cfg.get('containerName', cfg.apiSlug)
    def ports = cfg.get('ports', '-p 8290')
    def envFile = cfg.get('envFile', '')

    powershell """
        \$ErrorActionPreference = 'Stop'
        function Invoke-Docker {
          param([string[]]\$Arguments)
          & docker @Arguments
          if (\$LASTEXITCODE -ne 0) {
            throw "Docker command failed with exit code \${LASTEXITCODE}: docker \$(\$Arguments -join ' ')"
          }
        }
        \$portArgs = '${ports}' -split '\\s+' | Where-Object { -not [string]::IsNullOrWhiteSpace(\$_) }

        Invoke-Docker -Arguments @('pull', '${cfg.imageTag}')

        \$existing = docker ps -aq -f name='^/${containerName}\$'
        if (-not [string]::IsNullOrWhiteSpace(\$existing)) {
          Write-Host 'Existing container found for ${containerName}; stopping and removing'
          Invoke-Docker -Arguments @('stop', '${containerName}')
          Invoke-Docker -Arguments @('rm', '${containerName}')
        } else {
          Write-Host 'No existing container named ${containerName}; nothing to remove'
        }

        \$runArgs = @('run', '-d', '--restart', 'unless-stopped', '--name', '${containerName}') + \$portArgs
        if (-not [string]::IsNullOrWhiteSpace('${envFile}')) {
          \$runArgs += '${envFile}' -split '\\s+'
        }
        \$runArgs += '${cfg.imageTag}'
        Write-Host "Running: docker \$((\$runArgs -join ' '))"
        Invoke-Docker -Arguments \$runArgs

        Start-Sleep -Seconds 10
        \$running = docker ps --filter name='^/${containerName}\$' --filter status=running --format '{{.Names}}'
        if (\$running -ne '${containerName}') {
          throw 'Container ${containerName} is not running'
        }
    """
    def mappedPort = powershell(
        script: """
            \$mappings = docker port '${containerName}' 8290/tcp
            foreach (\$mapping in \$mappings) {
              if (\$mapping -match ':(\\d+)\$') {
                Write-Output \$Matches[1]
                break
              }
            }
        """,
        returnStdout: true
    ).trim()
    if (!mappedPort) {
        error "Could not determine mapped HTTP port for container ${containerName}"
    }
    return [
        containerName: containerName,
        baseUrl      : "http://localhost:${mappedPort}",
    ]
}

return this
