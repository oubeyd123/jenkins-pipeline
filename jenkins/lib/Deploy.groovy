def call(Map cfg) {
    def containerName = cfg.get('containerName', cfg.apiSlug)
    def ports = cfg.get('ports', '-p 8290')
    def envFile = cfg.get('envFile', '')

    powershell """
        \$ErrorActionPreference = 'Stop'
        function Invoke-Native {
          param([Parameter(ValueFromRemainingArguments = \$true)][object[]]\$Command)
          \$exe = \$Command[0]
          \$arguments = @()
          if (\$Command.Count -gt 1) {
            \$arguments = \$Command[1..(\$Command.Count - 1)]
          }
          & \$exe @arguments
          if (\$LASTEXITCODE -ne 0) {
            throw "Command failed with exit code \$LASTEXITCODE: \$Command"
          }
        }
        \$portArgs = '${ports}' -split '\\s+' | Where-Object { -not [string]::IsNullOrWhiteSpace(\$_) }

        Invoke-Native docker pull '${cfg.imageTag}'

        \$existing = docker ps -aq -f name='^/${containerName}\$'
        if (-not [string]::IsNullOrWhiteSpace(\$existing)) {
          Write-Host 'Existing container found for ${containerName}; stopping and removing'
          Invoke-Native docker stop '${containerName}'
          Invoke-Native docker rm '${containerName}'
        } else {
          Write-Host 'No existing container named ${containerName}; nothing to remove'
        }

        Invoke-Native docker run -d --restart unless-stopped --name '${containerName}' @portArgs ${envFile} '${cfg.imageTag}'

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
