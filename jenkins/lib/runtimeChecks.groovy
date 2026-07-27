def uniqueApiContexts(List apis) {
    def apiDirs = apis.collect { "apis/${it.path}/src/main/wso2mi/artifacts/apis" }
    def quotedDirs = apiDirs.collect { "'${it}'" }.join(' ')

    sh """
        set -euo pipefail

        tmp_file="\$(mktemp)"
        for api_dir in ${quotedDirs}; do
          if [ -d "\$api_dir" ]; then
            find "\$api_dir" -name '*.xml' -print0 |
              xargs -0 -r sed -n 's/.*<api[^>]* context="\\([^"]*\\)".*/\\1/p' >> "\$tmp_file"
          fi
        done

        duplicates="\$(sort "\$tmp_file" | uniq -d)"
        rm -f "\$tmp_file"

        if [ -n "\$duplicates" ]; then
          echo "Duplicate WSO2 API context(s) detected in changed APIs:"
          echo "\$duplicates"
          echo "Each API context deployed into the same MI container must be unique."
          exit 1
        fi
    """
}

return this
