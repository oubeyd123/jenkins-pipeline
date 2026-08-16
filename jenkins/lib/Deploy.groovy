def call(Map cfg) {
    def icpSecretCredentialId = "${env.ICP_SECRET_CRED_ID_PREFIX ?: 'icp-runtime-secret'}-${cfg.apiSlug}"
    withCredentials([string(credentialsId: icpSecretCredentialId, variable: 'ICP_RUNTIME_SECRET')]) {
        return deploy(cfg)
    }
}

def deploy(Map cfg) {
    def containerName = cfg.get('containerName', cfg.apiSlug)
    def ports = cfg.get('ports', '-p 8290')
    def envFile = cfg.get('envFile', '')
    def deployNetwork = env.DEPLOY_DOCKER_NETWORK ?: ''
    def serverHome = cfg.get('serverHome', env.WSO2_SERVER_HOME ?: '/home/wso2carbon/wso2mi-4.6.0')
    def icpUrl = env.ICP_URL
    def icpEnvironment = env.ICP_ENVIRONMENT ?: 'dev'
    def icpProject = env.ICP_PROJECT ?: 'wso2-mi-project'
    def icpContainerName = env.ICP_CONTAINER_NAME ?: 'integration-control-plane'
    def icpKeystorePath = env.ICP_KEYSTORE_PATH ?: '/home/wso2carbon/wso2-integration-control-plane-2.0.0/conf/security/wso2carbon.jks'
    def icpKeystoreAlias = env.ICP_KEYSTORE_ALIAS ?: 'wso2carbon'
    def icpKeystorePassword = env.ICP_KEYSTORE_PASSWORD ?: 'wso2carbon'
    def miTruststorePath = env.MI_TRUSTSTORE_PATH ?: "${serverHome}/repository/resources/security/client-truststore.jks"
    def miTruststorePassword = env.MI_TRUSTSTORE_PASSWORD ?: 'wso2carbon'

    powershell """
        \$ErrorActionPreference = 'Stop'
        function Invoke-Docker {
          param([string[]]\$Arguments)
          & docker @Arguments
          if (\$LASTEXITCODE -ne 0) {
            throw "Docker command failed with exit code \${LASTEXITCODE}: docker \$(\$Arguments -join ' ')"
          }
        }
        function Ensure-DeployNetwork {
          param([string]\$NetworkName, [string]\$IcpContainerName)

          if ([string]::IsNullOrWhiteSpace(\$NetworkName)) {
            return
          }

          \$existingNetwork = docker network ls --format '{{.Name}}' | Where-Object { \$_ -eq \$NetworkName }
          if (\$existingNetwork -ne \$NetworkName) {
            Write-Host "Creating Docker network \$NetworkName"
            Invoke-Docker -Arguments @('network', 'create', \$NetworkName)
          }

          \$members = @(docker network inspect \$NetworkName --format '{{range .Containers}}{{.Name}}{{println}}{{end}}')
          if (\$members -notcontains \$IcpContainerName) {
            Write-Host "Connecting \$IcpContainerName to Docker network \$NetworkName"
            Invoke-Docker -Arguments @('network', 'connect', \$NetworkName, \$IcpContainerName)
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
        function Set-MiHostname {
          param(
            [string]\$ContainerName,
            [string]\$ServerHome,
            [string]\$RuntimeName
          )

          Write-Host "Configuring MI server hostname as \$RuntimeName"
          \$hostConfig = Join-Path \$env:TEMP "deployment-\$ContainerName.toml"
          Invoke-Docker -Arguments @('cp', "\$ContainerName`:\$ServerHome/conf/deployment.toml", \$hostConfig)

          \$lines = [System.Collections.Generic.List[string]]::new()
          \$lines.AddRange([string[]](Get-Content -Path \$hostConfig))
          \$serverStart = -1
          for (\$i = 0; \$i -lt \$lines.Count; \$i++) {
            if (\$lines[\$i] -match '^\\[server\\]\\s*\$') {
              \$serverStart = \$i
              break
            }
          }

          if (\$serverStart -lt 0) {
            \$lines.Insert(0, "hostname = `"\$RuntimeName`"")
            \$lines.Insert(0, '[server]')
          } else {
            \$serverEnd = \$lines.Count
            for (\$i = \$serverStart + 1; \$i -lt \$lines.Count; \$i++) {
              if (\$lines[\$i] -match '^\\[[^\\]]+\\]\\s*\$') {
                \$serverEnd = \$i
                break
              }
            }

            \$hostnameIndex = -1
            for (\$i = \$serverStart + 1; \$i -lt \$serverEnd; \$i++) {
              if (\$lines[\$i] -match '^\\s*hostname\\s*=') {
                \$hostnameIndex = \$i
                break
              }
            }

            if (\$hostnameIndex -ge 0) {
              \$lines[\$hostnameIndex] = "hostname = `"\$RuntimeName`""
            } else {
              \$lines.Insert(\$serverStart + 1, "hostname = `"\$RuntimeName`"")
            }
          }

          Set-Content -Path \$hostConfig -Value \$lines -Encoding ascii
          Invoke-Docker -Arguments @('cp', \$hostConfig, "\$ContainerName`:\$ServerHome/conf/deployment.toml")
          Remove-Item -Path \$hostConfig -Force -ErrorAction SilentlyContinue
        }
        function Import-IcpCertificate {
          param([string]\$ContainerName)

          Write-Host "Importing ICP certificate into MI truststore"
          \$hostCert = Join-Path \$env:TEMP "icp-runtime-\$ContainerName.crt"
          Invoke-Docker -Arguments @(
            'exec',
            '${icpContainerName}',
            'keytool',
            '-exportcert',
            '-rfc',
            '-alias',
            '${icpKeystoreAlias}',
            '-keystore',
            '${icpKeystorePath}',
            '-storepass',
            '${icpKeystorePassword}',
            '-file',
            '/tmp/icp-runtime.crt'
          )
          Invoke-Docker -Arguments @('cp', '${icpContainerName}:/tmp/icp-runtime.crt', \$hostCert)
          Invoke-Docker -Arguments @('cp', \$hostCert, "\$ContainerName`:/tmp/icp-runtime.crt")
          & docker exec \$ContainerName keytool -delete -alias icp-runtime -keystore '${miTruststorePath}' -storepass '${miTruststorePassword}'
          if (\$LASTEXITCODE -ne 0) {
            Write-Host 'ICP certificate alias was not present in MI truststore; continuing with import'
          }
          Invoke-Docker -Arguments @(
            'exec',
            \$ContainerName,
            'keytool',
            '-importcert',
            '-noprompt',
            '-alias',
            'icp-runtime',
            '-file',
            '/tmp/icp-runtime.crt',
            '-keystore',
            '${miTruststorePath}',
            '-storepass',
            '${miTruststorePassword}'
          )
          Remove-Item -Path \$hostCert -Force -ErrorAction SilentlyContinue
        }

        \$portArgs = '${ports}' -split '\\s+' | Where-Object { -not [string]::IsNullOrWhiteSpace(\$_) }
        \$deployNetwork = '${deployNetwork}'
        Ensure-DeployNetwork -NetworkName \$deployNetwork -IcpContainerName '${icpContainerName}'

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
        if (-not [string]::IsNullOrWhiteSpace(\$deployNetwork)) {
          \$runArgs += @('--network', \$deployNetwork)
        }
        if (-not [string]::IsNullOrWhiteSpace('${envFile}')) {
          \$runArgs += '${envFile}' -split '\\s+'
        }
        \$runArgs += '${cfg.imageTag}'
        Write-Host "Running: docker \$((\$runArgs -join ' '))"
        Invoke-Docker -Arguments \$runArgs

        Start-Sleep -Seconds 10
        Set-MiHostname -ContainerName '${containerName}' -ServerHome '${serverHome}' -RuntimeName '${containerName}'
        Enable-IcpRegistration -ContainerName '${containerName}' -ServerHome '${serverHome}' -RuntimeName '${containerName}'
        Import-IcpCertificate -ContainerName '${containerName}'
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
