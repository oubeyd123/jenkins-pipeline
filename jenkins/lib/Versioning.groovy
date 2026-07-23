def nextVersion(String apiSlug, String apiPath) {
    def lastTag = sh(
        script: "git tag --list '${apiSlug}-v*' --sort=-v:refname | head -n1",
        returnStdout: true
    ).trim()

    def current = lastTag ? lastTag.replaceFirst(/^${apiSlug}-v/, '') : '0.0.0'
    def range = lastTag ? "${lastTag}..HEAD" : 'HEAD'

    def commits = sh(
        script: "git log ${range} --pretty=format:'%s|%h' -- 'apis/${apiPath}' 2>/dev/null || true",
        returnStdout: true
    ).trim()

    if (!commits) {
        return null 
    }

    def parts = current.tokenize('.').collect { it as int }
    int major = parts[0], minor = parts[1], patch = parts[2]

    String bump = 'patch'
    StringBuilder changelog = new StringBuilder()

    commits.split('\n').each { line ->
        def bits = line.split(/\|/, 2)
        if (bits.length < 2) return
        def (msg, sha) = bits
        changelog << "- ${msg} (${sha})\n"

        if (msg ==~ /(?i).*BREAKING CHANGE.*/ || msg ==~ /(?i)^[a-z]+(\([^)]*\))?!:.*/) {
            bump = 'major'
        } else if (msg ==~ /(?i)^feat(\([^)]*\))?:.*/ && bump != 'major') {
            bump = 'minor'
        }
    }

    switch (bump) {
        case 'major': major += 1; minor = 0; patch = 0; break
        case 'minor': minor += 1; patch = 0; break
        case 'patch': patch += 1; break
    }

    String newVersion = "${major}.${minor}.${patch}"
    return [
        version  : newVersion,
        tag      : "${apiSlug}-v${newVersion}",
        changelog: changelog.toString(),
        bump     : bump,
    ]
}

def tagAndRelease(String tag, String changelogBody) {
    def exists = sh(
        script: "git ls-remote --exit-code --tags origin refs/tags/${tag} >/dev/null 2>&1",
        returnStatus: true
    ) == 0

    if (exists) {
        echo "Tag ${tag} already exists remotely — reusing it (idempotent re-run)"
    } else {
        sh "git tag ${tag}"
        sh "git push origin ${tag}"
    }

  
    def notesFile = "release-notes-${tag}.txt"
    writeFile file: notesFile, text: changelogBody
    archiveArtifacts artifacts: notesFile, fingerprint: true
}

return this