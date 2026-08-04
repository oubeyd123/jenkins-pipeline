def call(Object apiInput) {
    def apis = apiInput instanceof List ? apiInput : [apiInput]
    def apiDirs = apis.collect { "apis/${it.path}/src/main/wso2mi/artifacts/apis" }
    def quotedDirs = apiDirs.collect { "'${it}'" }.join(' ')

    return sh(
        script: """
            set -euo pipefail

            for api_dir in ${quotedDirs}; do
              if [ ! -d "\$api_dir" ]; then
                continue
              fi

              for api_file in \$(find "\$api_dir" -name '*.xml'); do
                context=\$(sed -n 's/.*<api[^>]* context="\\([^"]*\\)".*/\\1/p' "\$api_file" | head -n1)
                if [ -z "\$context" ]; then
                  continue
                fi

                sed -n 's/.*<resource[^>]* methods="\\([^"]*\\)"[^>]* uri-template="\\([^"]*\\)".*/\\1|\\2/p' "\$api_file" |
                while IFS='|' read -r methods uri; do
                  for method in \$methods; do
                    if [ -z "\$method" ]; then
                      continue
                    fi

                    if [ "\$uri" = "/" ] || [ -z "\$uri" ]; then
                      printf '%s|%s\\n' "\$method" "\$context"
                    else
                      printf '%s|%s%s\\n' "\$method" "\$context" "\$uri"
                    fi
                  done
                done
              done
            done | sort -u
        """,
        returnStdout: true
    ).trim()
}

return this
