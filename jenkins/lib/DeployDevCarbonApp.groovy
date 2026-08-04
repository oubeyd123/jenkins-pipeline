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

        \$existing = docker ps -aq -f name="^/\$containerName`\$"
        if ([string]::IsNullOrWhiteSpace(\$existing)) {
          Write-Host "Dev MI container \$containerName does not exist; starting it from \$baseImage"
          docker pull "\$baseImage"
          docker run -d --restart unless-stopped --name "\$containerName" @portArgs "\$baseImage"
        } else {
          \$running = docker ps -q -f name="^/\$containerName`\$" -f status=running
          if ([string]::IsNullOrWhiteSpace(\$running)) {
            Write-Host "Dev MI container \$containerName exists but is stopped; starting it"
            docker start "\$containerName" | Out-Host
          } else {
            Write-Host "Reusing running dev MI container \$containerName"
          }
        }

        Start-Sleep -Seconds 10

        Write-Host "Cleaning old CAR files and temporary CApp deployment data from \$containerName"
        docker exec "\$containerName" sh -c "rm -f '\$carbonAppsDir/'*.car && rm -rf '${serverHome}/tmp/carbonapps/'*"

        if (Test-Path 'target\\dev-libs') {
          \$jars = Get-ChildItem -Path 'target\\dev-libs' -Filter '*.jar' -File -ErrorAction SilentlyContinue
          foreach (\$jar in \$jars) {
            Write-Host "Copying runtime library \$([System.IO.Path]::GetFileName(\$jar.FullName))"
            docker cp "\$([System.IO.Path]::GetFullPath(\$jar.FullName))" "\$containerName`:\$libsDir/"
          }
        }

        Write-Host 'Restarting MI container to clear previous runtime artifact state'
        docker restart "\$containerName" | Out-Host
        Start-Sleep -Seconds 20

        \$cars = Get-ChildItem -Path 'target\\dev-carbonapps' -Filter '*.car' -File
        if (\$cars.Count -eq 0) {
          throw 'No CAR file found in target\\dev-carbonapps'
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
