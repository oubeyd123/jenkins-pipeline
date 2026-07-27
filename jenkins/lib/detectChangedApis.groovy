def call(String diffRange) {
    def safeDiffRange = diffRange.replace("'", "'\"'\"'")
    def fetchBaseRef = ''

    if (diffRange != 'HEAD') {
        def baseRef = diffRange.replaceFirst(/\.\.\..*$/, '').replaceFirst(/\.\..*$/, '')
        if (baseRef.startsWith('origin/')) {
            def safeBaseRef = baseRef.replace("'", "'\"'\"'")
            def branchName = baseRef.substring('origin/'.length()).replace("'", "'\"'\"'")
            fetchBaseRef = """
                if ! git rev-parse --verify --quiet '${safeBaseRef}' >/dev/null; then
                  git fetch --no-tags origin '+refs/heads/${branchName}:refs/remotes/origin/${branchName}'
                fi
            """
        }
    }

    def output = sh(
        script: """
            set -euo pipefail

            if [ '${safeDiffRange}' = 'HEAD' ]; then
              git diff-tree --no-commit-id --name-only -r -m HEAD -- 'apis/**'
            else
              ${fetchBaseRef}
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
