def call(Map cfg) {
    sh """
        set -e
        docker pull ${cfg.imageTag}
 
        if [ \$(docker ps -aq -f name=^${cfg.apiSlug}\$) ]; then
          echo "Existing container found for ${cfg.apiSlug} — stopping and removing"
          docker stop ${cfg.apiSlug} || true
          docker rm ${cfg.apiSlug} || true
        else
          echo "No existing container named ${cfg.apiSlug} — nothing to remove"
        fi
 
        docker run -d --name ${cfg.apiSlug} ${cfg.imageTag}
 
        sleep 3
        docker ps --filter "name=${cfg.apiSlug}" --filter "status=running" --format '{{.Names}}' | grep -q "^${cfg.apiSlug}\$" \
          || { echo "::error:: ${cfg.apiSlug} container did not stay running"; exit 1; }
    """
}
 
return this