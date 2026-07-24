def call(Map cfg) {
    def containerName = cfg.get('containerName', cfg.apiSlug)
    def ports = cfg.get('ports', '')
    def envFile = cfg.get('envFile', '')
    def healthCommand = cfg.get('healthCommand', "docker ps --filter name=^/${containerName}\$ --filter status=running --format '{{.Names}}' | grep -q '^${containerName}\$'")

    sh """
        set -euo pipefail

        docker pull '${cfg.imageTag}'

        if docker ps -aq -f name=^/${containerName}\$ | grep -q .; then
          echo "Existing container found for ${containerName}; stopping and removing"
          docker stop '${containerName}' || true
          docker rm '${containerName}' || true
        else
          echo "No existing container named ${containerName}; nothing to remove"
        fi

        docker run -d --restart unless-stopped --name '${containerName}' ${ports} ${envFile} '${cfg.imageTag}'

        sleep 10
        ${healthCommand}
    """
}

return this
