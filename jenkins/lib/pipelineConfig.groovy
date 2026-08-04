def call(String configPath = 'jenkins/config/pipeline.properties') {
    if (!fileExists(configPath)) {
        error "Pipeline config file not found: ${configPath}"
    }

    readFile(configPath)
        .split('\\r?\\n')
        .collect { it.trim() }
        .findAll { it && !it.startsWith('#') }
        .each { line ->
            def separator = line.indexOf('=')
            if (separator <= 0) {
                error "Invalid pipeline config line in ${configPath}: ${line}"
            }

            applyConfigValue(
                line.substring(0, separator).trim(),
                line.substring(separator + 1).trim()
            )
        }
}

def applyConfigValue(String key, String value) {
    switch (key) {
        case 'REGISTRY':
            env.REGISTRY = env.REGISTRY ?: value
            break
        case 'REGISTRY_CRED_ID':
            env.REGISTRY_CRED_ID = env.REGISTRY_CRED_ID ?: value
            break
        case 'GIT_CRED_ID':
            env.GIT_CRED_ID = env.GIT_CRED_ID ?: value
            break
        case 'BUILD_AGENT_LABEL':
            env.BUILD_AGENT_LABEL = env.BUILD_AGENT_LABEL ?: value
            break
        case 'DEV_DEPLOY_AGENT_LABEL':
            env.DEV_DEPLOY_AGENT_LABEL = env.DEV_DEPLOY_AGENT_LABEL ?: value
            break
        case 'DEPLOY_AGENT_LABEL':
            env.DEPLOY_AGENT_LABEL = env.DEPLOY_AGENT_LABEL ?: value
            break
        case 'SMOKE_BASE_URL':
            env.SMOKE_BASE_URL = env.SMOKE_BASE_URL ?: value
            break
        case 'DEV_CONTAINER_NAME':
            env.DEV_CONTAINER_NAME = env.DEV_CONTAINER_NAME ?: value
            break
        case 'DEV_CONTAINER_PORTS':
            env.DEV_CONTAINER_PORTS = env.DEV_CONTAINER_PORTS ?: value
            break
        case 'WSO2_BASE_IMAGE':
            env.WSO2_BASE_IMAGE = env.WSO2_BASE_IMAGE ?: value
            break
        case 'WSO2_SERVER_HOME':
            env.WSO2_SERVER_HOME = env.WSO2_SERVER_HOME ?: value
            break
        case 'TRIVY_FS_CACHE_DIR':
            env.TRIVY_FS_CACHE_DIR = env.TRIVY_FS_CACHE_DIR ?: value
            break
        case 'TRIVY_IMAGE_CACHE_DIR':
            env.TRIVY_IMAGE_CACHE_DIR = env.TRIVY_IMAGE_CACHE_DIR ?: value
            break
        case 'TRIVY_SKIP_DB_UPDATE':
            env.TRIVY_SKIP_DB_UPDATE = env.TRIVY_SKIP_DB_UPDATE ?: value
            break
        case 'TRIVY_TIMEOUT':
            env.TRIVY_TIMEOUT = env.TRIVY_TIMEOUT ?: value
            break
        case 'FAILURE_EMAIL_RECIPIENTS':
            env.FAILURE_EMAIL_RECIPIENTS = env.FAILURE_EMAIL_RECIPIENTS ?: value
            break
        default:
            error "Unsupported pipeline config key: ${key}"
    }
}

return this
