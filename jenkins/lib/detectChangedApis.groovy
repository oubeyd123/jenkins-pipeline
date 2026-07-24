def call(String diffRange) {
    def safeDiffRange = diffRange.replace("'", "'\"'\"'")
    def output = sh(
        script: """
            set -euo pipefail

            if [ '${safeDiffRange}' = 'HEAD' ]; then
              git show --name-only --pretty=format: HEAD -- 'apis/**'
            else
              git diff --name-only '${safeDiffRange}' -- 'apis/**'
            fi |
            awk -F/ 'NF >= 4 { print \$2 "/" \$3 }' |
            sort -u
        """,
        returnStdout: true
    ).trim()

    if (!output) {
        return []
    }

    return output
        .split('\n')
        .collect { it.trim() }
        .findAll { it }
        .collect { path ->
            [path: path, slug: path.tokenize('/').join('-')]
        }
}

return this
