def call(String containerName) {
    powershell """
        \$ErrorActionPreference = 'Stop'
        \$containerName = '${containerName}'
        \$existing = docker ps -aq -f name="^/\$containerName`\$"
        if (-not [string]::IsNullOrWhiteSpace(\$existing)) {
          Write-Host "Removing temporary dev MI container \$containerName"
          docker rm -f \$containerName
          if (\$LASTEXITCODE -ne 0) {
            throw "Failed to remove temporary dev MI container \$containerName"
          }
        } else {
          Write-Host "Temporary dev MI container \$containerName already removed"
        }
    """
}

return this
