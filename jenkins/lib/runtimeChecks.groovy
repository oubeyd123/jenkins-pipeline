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

def validate(List apis) {
    uniqueApiContexts(apis)
    uniqueLocalEntries(apis)
    noApiNameLocalEntryConflicts(apis)
}

def uniqueLocalEntries(List apis) {
    def entryDirs = apis.collect { "apis/${it.path}/src/main/wso2mi/artifacts/local-entries" }
    def quotedDirs = entryDirs.collect { "'${it}'" }.join(' ')

    sh """
        set -euo pipefail

        tmp_file="\$(mktemp)"
        for entry_dir in ${quotedDirs}; do
          if [ -d "\$entry_dir" ]; then
            find "\$entry_dir" -name '*.xml' -print0 |
              xargs -0 -r sed -n 's/.*<localEntry[^>]* key="\\([^"]*\\)".*/\\1/p' >> "\$tmp_file"
          fi
        done

        duplicates="\$(sort "\$tmp_file" | uniq -d)"
        rm -f "\$tmp_file"

        if [ -n "\$duplicates" ]; then
          echo "Duplicate WSO2 local-entry key(s) detected in changed APIs:"
          echo "\$duplicates"
          echo "Each localEntry key deployed into the same MI container must be unique."
          exit 1
        fi
    """
}

def noApiNameLocalEntryConflicts(List apis) {
    def apiPaths = apis.collect { "apis/${it.path}" }
    def quotedPaths = apiPaths.collect { "'${it}'" }.join(' ')

    sh """
        set -euo pipefail

        failed=0
        for api_path in ${quotedPaths}; do
          api_dir="\$api_path/src/main/wso2mi/artifacts/apis"
          entry_dir="\$api_path/src/main/wso2mi/artifacts/local-entries"

          if [ ! -d "\$api_dir" ] || [ ! -d "\$entry_dir" ]; then
            continue
          fi

          api_names="\$(mktemp)"
          entry_keys="\$(mktemp)"

          find "\$api_dir" -name '*.xml' -print0 |
            xargs -0 -r sed -n 's/.*<api[^>]* name="\\([^"]*\\)".*/\\1/p' |
            sort -u > "\$api_names"

          find "\$entry_dir" -name '*.xml' -print0 |
            xargs -0 -r sed -n 's/.*<localEntry[^>]* key="\\([^"]*\\)".*/\\1/p' |
            sort -u > "\$entry_keys"

          conflicts="\$(comm -12 "\$api_names" "\$entry_keys")"
          rm -f "\$api_names" "\$entry_keys"

          if [ -n "\$conflicts" ]; then
            echo "WSO2 artifact name conflict detected in \$api_path:"
            echo "\$conflicts"
            echo "An API name and a localEntry key inside the same CAR must not use the same name."
            failed=1
          fi
        done

        exit "\$failed"
    """
}

return this
