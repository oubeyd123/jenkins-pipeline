def call(String apiPath) {
    dir("apis/${apiPath}") {
        sh '''
            set -euo pipefail

            mvn -B validate

            if command -v xmllint >/dev/null 2>&1; then
              find src/main/wso2mi -name '*.xml' -print0 | xargs -0 -r xmllint --noout
            else
              echo "xmllint not installed; skipping XML syntax validation"
            fi

            if command -v yamllint >/dev/null 2>&1; then
              {
                find . -name '*.yaml' \
                  ! -path './target/*' \
                  ! -path './build/*' \
                  ! -path './tmp/*' \
                  ! -path '*/resources/metadata/*' \
                  ! -path '*/resources/api-definitions/*' \
                  -print0
                find . -name '*.yml' \
                  ! -path './target/*' \
                  ! -path './build/*' \
                  ! -path './tmp/*' \
                  ! -path '*/resources/metadata/*' \
                  ! -path '*/resources/api-definitions/*' \
                  -print0
              } | xargs -0 -r yamllint -c "${WORKSPACE:-../../..}/.yamllint"
            else
              echo "yamllint not installed; skipping YAML syntax validation"
            fi
        '''
    }
}

return this
