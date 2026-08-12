def call(Map cfg) {
    withCredentials([string(credentialsId: env.ICP_SECRET_CRED_ID ?: 'icp-runtime-secret', variable: 'ICP_RUNTIME_SECRET')]) {
        return deploy(cfg)
    }
}

def deploy(Map cfg) {
    def containerName = cfg.get('containerName', cfg.apiSlug)
    def ports = cfg.get('ports', '-p 8290')
    def envFile = cfg.get('envFile', '')
    def serverHome = cfg.get('serverHome', env.WSO2_SERVER_HOME ?: '/home/wso2carbon/wso2mi-4.6.0')
    def icpUrl = env.ICP_URL
    def icpEnvironment = env.ICP_ENVIRONMENT ?: 'dev'
    def icpProject = env.ICP_PROJECT ?: 'wso2-mi-project'

    powershell """
        \$ErrorActionPreference = 'Stop'
        function Invoke-Docker {
          param([string[]]\$Arguments)
          & docker @Arguments
          if (\$LASTEXITCODE -ne 0) {
            throw "Docker command failed with exit code \${LASTEXITCODE}: docker \$(\$Arguments -join ' ')"
          }
        }
        function Enable-IcpRegistration {
          param(
            [string]\$ContainerName,
            [string]\$ServerHome,
            [string]\$RuntimeName
          )

          if ([string]::IsNullOrWhiteSpace(\$env:ICP_RUNTIME_SECRET)) {
            throw 'ICP is enabled, but ICP_RUNTIME_SECRET is empty'
          }

          Write-Host "Configuring ICP registration for \$RuntimeName"
          \$tmpFile = Join-Path \$env:TEMP "icp-\$ContainerName.toml"
          \$toml = @"

[icp_config]
enabled = true
environment = "${icpEnvironment}"
project = "${icpProject}"
integration = "${cfg.apiSlug}"
runtime = "\$RuntimeName"
secret = "\$env:ICP_RUNTIME_SECRET"
icp_url = "${icpUrl}"
"@
          Set-Content -Path \$tmpFile -Value \$toml -Encoding ascii
          Invoke-Docker -Arguments @('cp', \$tmpFile, "\$ContainerName`:/tmp/icp-config.toml")
          Invoke-Docker -Arguments @('exec', \$ContainerName, 'sh', '-c', "cat /tmp/icp-config.toml >> '\$ServerHome/conf/deployment.toml'")
          Remove-Item -Path \$tmpFile -Force -ErrorAction SilentlyContinue
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
        Enable-IcpRegistration -ContainerName '${containerName}' -ServerHome '${serverHome}' -RuntimeName '${containerName}'
        Write-Host 'Restarting MI so ICP settings are loaded'
        Invoke-Docker -Arguments @('restart', '${containerName}')
        Start-Sleep -Seconds 20

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
