def prepare(String apiPath) {
    dir("apis/${apiPath}") {
        sh 'rm -rf target/mi-runtime-libs && mkdir -p target/mi-runtime-libs && touch target/mi-runtime-libs/.dockerkeep'

        if (!fileExists('pom.xml')) {
            echo "No pom.xml found for ${apiPath}; skipping MI runtime library resolution"
            return
        }

        def pom = readFile('pom.xml')
        if (!pom.contains('<id>mi-runtime-libs</id>')) {
            echo "No mi-runtime-libs Maven profile found for ${apiPath}; skipping MI runtime library resolution"
            return
        }

        sh """
            set -euo pipefail
            mvn -B -Pmi-runtime-libs dependency:copy-dependencies \\
              -DincludeScope=runtime \\
              -DincludeTypes=jar \\
              -DoutputDirectory=target/mi-runtime-libs
        """
    }
}

return this
