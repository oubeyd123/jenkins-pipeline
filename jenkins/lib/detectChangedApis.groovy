def call(String diffRange) {
    def safeDiffRange = diffRange.replace("'", "'\"'\"'")
    def output = sh(
        script: """
            set -euo pipefail

            if [ '${safeDiffRange}' = 'HEAD' ]; then
              git diff-tree --no-commit-id --name-only -r -m HEAD -- 'apis/**'
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
