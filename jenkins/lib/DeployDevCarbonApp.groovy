def call(Map cfg) {
    def containerName = cfg.containerName
    def ports = cfg.ports
    def baseImage = cfg.baseImage
    def serverHome = cfg.serverHome
    def carbonAppsDir = "${serverHome}/repository/deployment/server/carbonapps"
    def libsDir = "${serverHome}/lib"

    powershell """
        \$ErrorActionPreference = 'Stop'

        \$containerName = '${containerName}'
        \$baseImage = '${baseImage}'
        \$ports = '${ports}'
        \$portArgs = \$ports -split '\\s+' | Where-Object { -not [string]::IsNullOrWhiteSpace(\$_) }
        \$carbonAppsDir = '${carbonAppsDir}'
        \$libsDir = '${libsDir}'

        if (Test-Path 'target\\dev-libs') {
          \$jars = Get-ChildItem -Path 'target\\dev-libs' -Filter '*.jar' -File -ErrorAction SilentlyContinue
        } else {
          \$jars = @()
        }

        \$cars = Get-ChildItem -Path 'target\\dev-carbonapps' -Filter '*.car' -File
        if (\$cars.Count -eq 0) {
          throw 'No CAR file found in target\\dev-carbonapps'
        }

        \$existing = docker ps -aq -f name="^/\$containerName`\$"
        if (-not [string]::IsNullOrWhiteSpace(\$existing)) {
          Write-Host "Removing existing dev MI container \$containerName to guarantee clean runtime state"
          docker rm -f "\$containerName" | Out-Host
        }

        Write-Host "Starting clean dev MI container \$containerName from \$baseImage"
        docker pull "\$baseImage"
        docker run -d --restart unless-stopped --name "\$containerName" @portArgs "\$baseImage"

        Start-Sleep -Seconds 10

        foreach (\$jar in \$jars) {
          Write-Host "Copying runtime library \$([System.IO.Path]::GetFileName(\$jar.FullName))"
          docker cp "\$([System.IO.Path]::GetFullPath(\$jar.FullName))" "\$containerName`:\$libsDir/"
        }

        if (\$jars.Count -gt 0) {
          Write-Host 'Runtime libraries were copied; restarting MI so the JVM loads them'
          docker restart "\$containerName" | Out-Host
          Start-Sleep -Seconds 20
        }

        foreach (\$car in \$cars) {
          Write-Host "Copying CAR \$([System.IO.Path]::GetFileName(\$car.FullName))"
          docker cp "\$([System.IO.Path]::GetFullPath(\$car.FullName))" "\$containerName`:\$carbonAppsDir/"
        }

        Start-Sleep -Seconds 15
        \$running = docker ps --filter name="^/\$containerName`\$" --filter status=running --format '{{.Names}}'
        if (\$running -ne \$containerName) {
          throw "Dev MI container \$containerName is not running"
        }
    """
}

return this
