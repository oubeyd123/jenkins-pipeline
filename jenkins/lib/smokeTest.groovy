def call(Map cfg) {
    if (!cfg.url) {
        echo "No smoke test URL configured for ${cfg.apiSlug}; skipping smoke test"
        return
    }

    sh """
        set -euo pipefail
        curl --fail --silent --show-error --retry 5 --retry-delay 5 '${cfg.url}'
    """
}

return this
