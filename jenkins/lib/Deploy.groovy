def call(Map cfg) {
    def containerName = cfg.get('containerName', cfg.apiSlug)
    def ports = cfg.get('ports', '-p 8290')
    def envFile = cfg.get('envFile', '')
    def healthCommand = cfg.get('healthCommand', "docker ps --filter name=^/${containerName}\$ --filter status=running --format '{{.Names}}' | grep -q '^${containerName}\$'")

    if (isUnix()) {
        sh """
            set -euo pipefail

            docker pull '${cfg.imageTag}'

            if docker ps -aq -f name=^/${containerName}\$ | grep -q .; then
              echo "Existing container found for ${containerName}; stopping and removing"
              docker stop '${containerName}' || true
              docker rm '${containerName}' || true
            else
              echo "No existing container named ${containerName}; nothing to remove"
            fi

            docker run -d --restart unless-stopped --name '${containerName}' ${ports} ${envFile} '${cfg.imageTag}'

            sleep 10
            ${healthCommand}
        """
        def mappedPort = sh(
            script: "docker port '${containerName}' 8290/tcp | sed -n 's/.*://p' | head -n1",
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

    powershell """
        \$ErrorActionPreference = 'Stop'

        docker pull '${cfg.imageTag}'

        \$existing = docker ps -aq -f name='^/${containerName}\$'
        if (-not [string]::IsNullOrWhiteSpace(\$existing)) {
          Write-Host 'Existing container found for ${containerName}; stopping and removing'
          docker stop '${containerName}' | Out-Host
          docker rm '${containerName}' | Out-Host
        } else {
          Write-Host 'No existing container named ${containerName}; nothing to remove'
        }

        docker run -d --restart unless-stopped --name '${containerName}' ${ports} ${envFile} '${cfg.imageTag}'

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
