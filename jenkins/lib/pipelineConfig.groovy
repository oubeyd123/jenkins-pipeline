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

            def key = line.substring(0, separator).trim()
            def value = line.substring(separator + 1).trim()
            if (!(env[key] ?: '').trim()) {
                env[key] = value
            }
        }
}

return this
