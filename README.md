# WSO2 MI Jenkins CI/CD

Jenkins multibranch pipeline for WSO2 Micro Integrator 4.6.0 APIs.

The pipeline detects changed APIs, validates them, builds CAR files, scans source and Docker images, deploys containers, runs smoke tests, creates releases on `main`, and exposes Jenkins metrics to Prometheus/Grafana.

## Repository Layout

```text
apis/
  <category>/<api-name>/
    pom.xml
    src/main/wso2mi/
    deployment/docker/Dockerfile

jenkins/
  config/pipeline.properties
  lib/*.groovy

monitoring/grafana/
  wso2-mi-jenkins-dashboard.json
```

API paths become pipeline slugs. Example:

```text
apis/order-api/product-api -> order-api-product-api
```

The slug is used for Jenkins stages, Docker images, Git tags, GitHub releases, and archived reports.

## Branch Flow

```text
feature/* -> develop -> main
```

| Branch | Purpose | Heavy Actions |
|--------|---------|---------------|
| `feature/*` / PR | Fast validation before merge | no deploy, no image push |
| `develop` | Integration test with real MI containers | deploy changed APIs to temporary containers and smoke test |
| `main` | Release | version, tag, GitHub release, image push, image scan, deploy, smoke test |

Keep long-term branches simple:

```text
main
develop
```

## Pipeline Stages

For each changed API, Jenkins runs:

```text
Validate
Gitleaks + Trivy filesystem scan
Build CAR
Docker build check
Release steps on main only
Trivy image scan on main only
Deploy
Smoke test
Cleanup, for develop temporary containers
```

Parallel API behavior:

```text
Each changed API runs in its own parallel branch.
If one API fails, the other API branches continue.
The failed API is still marked failed in Jenkins.
```

## Required Tools

Linux Jenkins side:

```text
git
maven
java
xmllint
yamllint
gitleaks
trivy
```

Windows Docker agent:

```powershell
docker --version
trivy --version
java --version
mvn --version
```

The Windows agent label is configured in:

```text
jenkins/config/pipeline.properties
```

Default:

```text
DEV_DEPLOY_AGENT_LABEL=wso2-dev-server
DEPLOY_AGENT_LABEL=wso2-dev-server
```

## Start Required Containers

Recommended quick start:

```powershell
docker compose up -d
```

This starts:

```text
jenkins
nexus
integration-control-plane
prometheus
grafana
```

Service URLs:

| Service | URL |
|---------|-----|
| Jenkins | `http://localhost:8080` |
| Nexus | `http://localhost:8081` |
| Integration Control Plane | `https://localhost:9446` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` |

Stop the stack:

```powershell
docker compose down
```

The manual commands below are kept for cases where you want to start one service at a time.

### Jenkins

```powershell
docker volume create jenkins_home

docker run -d --name jenkins `
  -p 8080:8080 `
  -p 50000:50000 `
  -v jenkins_home:/var/jenkins_home `
  jenkins/jenkins:lts
```

Open:

```text
http://localhost:8080
```

GitHub webhook:

```text
http://<jenkins-url>/github-webhook/
```

For a local Jenkins exposed through ngrok:

```powershell
ngrok http 8080
```

Use webhook events:

```text
push
pull_request
```

### Nexus

Nexus stores custom runtime JARs used by WSO2 MI APIs.

```powershell
docker volume create nexus-data

docker run -d --name nexus `
  -p 8081:8081 `
  -v nexus-data:/nexus-data `
  sonatype/nexus3:latest
```

Open:

```text
http://localhost:8081
```

Recommended repositories:

```text
wso2-mi-libs-releases
wso2-mi-libs-snapshots
```

Jenkins Maven settings must contain Nexus credentials:

```text
/var/jenkins_home/.m2/settings.xml
```

### Integration Control Plane

WSO2 Integration Control Plane, or ICP, is optional. It is used to observe and manage WSO2 MI runtimes and the artifacts deployed inside them.

Docker Compose starts it with:

```text
wso2/wso2-integration-control-plane:2.0.0-rocky
```

Open:

```text
https://localhost:9446
```

Runtime communication port:

```text
9445
```

Important: starting ICP is not enough by itself. Each MI runtime/container must be configured to connect to ICP before it appears in the control plane.

Pipeline registration:

```text
Jenkins can inject the ICP configuration into main release MI containers.
The setting is written inside the container at:
/home/wso2carbon/wso2mi-4.6.0/conf/deployment.toml
```

ICP registration is active only for `main` release deployments. Develop containers are temporary and are deleted after smoke tests, so they are not registered in ICP.

Setup:

```text
1. Open ICP and copy the MI runtime secret.
2. Add the secret to Jenkins Credentials as Secret text.
3. Use credential IDs with this format: icp-runtime-secret-<api-slug>.
4. Make sure the Docker network exists: docker network create wso2-mi-net
5. Make sure the ICP certificate includes integration-control-plane as a DNS SAN.
6. Run the main pipeline so Jenkins starts the MI containers with ICP config.
```

Pipeline properties:

```text
DEPLOY_DOCKER_NETWORK=wso2-mi-net
ICP_URL=https://integration-control-plane:9445
ICP_ENVIRONMENT=dev
ICP_PROJECT=wso2-mi-project
ICP_SECRET_CRED_ID_PREFIX=icp-runtime-secret
ICP_CONTAINER_NAME=integration-control-plane
ICP_KEYSTORE_PATH=/home/wso2carbon/wso2-integration-control-plane-2.0.0/conf/security/wso2carbon.jks
ICP_KEYSTORE_ALIAS=wso2carbon
ICP_KEYSTORE_PASSWORD=wso2carbon
MI_TRUSTSTORE_PATH=/home/wso2carbon/wso2mi-4.6.0/repository/resources/security/client-truststore.jks
MI_TRUSTSTORE_PASSWORD=wso2carbon
```

For local Docker testing, release MI containers and ICP share the same Docker network:

```text
wso2-mi-net
```

This lets containers reach each other by name:

```text
MI -> ICP: https://integration-control-plane:9445
ICP -> MI management API: https://<api-slug>-release:9164
```

Jenkins creates the network if it is missing and connects the ICP container to it during `main` deployments. It also runs release MI containers on that network and sets the MI hostname to the release container name.

The ICP certificate must contain these DNS names:

```text
localhost
host.docker.internal
integration-control-plane
```

The integration name is automatically set to the API slug, for example:

```text
order-api-product-api
```

ICP runtime secrets are selected from Jenkins credentials by API slug:

```text
icp-runtime-secret-customer-api-customerapi
icp-runtime-secret-greeding-api-test
icp-runtime-secret-order-api-math
icp-runtime-secret-order-api-product-api
icp-runtime-secret-payment-api-salerie-api
```

Official documentation:

```text
https://mi.docs.wso2.com/en/latest/observe-and-manage/working-with-integration-control-plane/
```

### Prometheus

Prometheus scrapes Jenkins metrics from the Jenkins Prometheus plugin.

Create:

```powershell
notepad C:\tmp\prometheus.yml
```

Content:

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: jenkins
    metrics_path: /prometheus
    static_configs:
      - targets:
          - host.docker.internal:8080
```

Run:

```powershell
docker volume create prometheus-data

docker run -d --name prometheus `
  -p 9090:9090 `
  -v C:\tmp\prometheus.yml:/etc/prometheus/prometheus.yml:ro `
  -v prometheus-data:/prometheus `
  prom/prometheus:latest
```

Check:

```text
http://localhost:9090/targets
```

Expected:

```text
jenkins = UP
```

### Grafana

```powershell
docker volume create grafana-data

docker run -d --name grafana `
  -p 3000:3000 `
  -v grafana-data:/var/lib/grafana `
  grafana/grafana:latest
```

Open:

```text
http://localhost:3000
```

Default login:

```text
admin / admin
```

Add Prometheus datasource:

```text
http://host.docker.internal:9090
```

Import dashboard:

```text
monitoring/grafana/wso2-mi-jenkins-dashboard.json
```

## Build API Containers Manually

The pipeline normally builds images automatically. For local testing, first build the API CAR:

```powershell
cd apis\order-api\product-api
mvn -B clean verify
```

Prepare the Docker context:

```powershell
New-Item -ItemType Directory -Force -Path CompositeApps | Out-Null
New-Item -ItemType Directory -Force -Path resources | Out-Null
New-Item -ItemType Directory -Force -Path libs | Out-Null
New-Item -ItemType File -Force -Path libs\.dockerkeep | Out-Null

Copy-Item -Path target\*.car -Destination CompositeApps\ -Force

if (Test-Path target\mi-runtime-libs) {
  Copy-Item -Path target\mi-runtime-libs\*.jar -Destination libs\ -Force -ErrorAction SilentlyContinue
}

if (Test-Path deployment\docker\resources) {
  Copy-Item -Path deployment\docker\resources\* -Destination resources\ -Recurse -Force
}
```

Build the container image:

```powershell
docker build `
  --build-arg BASE_IMAGE=wso2/wso2mi:4.6.0 `
  --build-arg WSO2_SERVER_HOME=/home/wso2carbon/wso2mi-4.6.0 `
  -f deployment/docker/Dockerfile `
  -t local/order-api-product-api:test .
```

Run it:

```powershell
docker run -d --name order-api-product-api-test `
  -p 8290:8290 `
  -p 8253:8253 `
  local/order-api-product-api:test
```

Check logs:

```powershell
docker logs order-api-product-api-test
```

Test an API:

```powershell
curl.exe http://localhost:8290/helloapi
```

Clean up:

```powershell
docker rm -f order-api-product-api-test
```

## WSO2 MI Image Layout

Base image:

```text
wso2/wso2mi:4.6.0
```

CAR files are copied to:

```text
/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/
```

Runtime JARs are copied to:

```text
/home/wso2carbon/wso2mi-4.6.0/lib/
```

When the container starts, WSO2 MI deploys the CAR files automatically.

## Quality And Security

Quality checks:

```text
mvn validate
xmllint for WSO2 XML files
yamllint for developer-written YAML files
```

Security checks:

```text
Gitleaks  -> secret scanning
Trivy FS  -> source dependency/configuration scan
Trivy image -> Docker image vulnerability scan
```

Policy:

```text
Gitleaks secrets fail the pipeline.
Trivy filesystem HIGH and CRITICAL findings fail the pipeline.
Trivy image CRITICAL findings fail the pipeline.
Trivy image HIGH findings are reported.
```

Reports are archived under:

```text
target/security-reports/
```

## AI Failure Analysis

The project includes an optional AI CI/CD Log Analyzer under:

```text
tools/ai-log-analyzer
```

When Jenkins fails, the `post { failure { ... } }` block extracts the important console-log error sections, redacts common secrets, archives the extracted JSON, and sends it to the backend when this variable is configured:

```text
AI_ANALYZER_URL=http://ai-log-analyzer:8000
```

Backend endpoints:

```text
POST /api/analyze
GET  /api/latest-failure
GET  /api/failures
GET  /api/failures/{id}
```

The first version stores results in SQLite and uses a local deterministic analyzer. A real AI provider can be connected later from the backend without exposing API keys to Jenkins or the Chrome extension.

## Trivy Cache

Linux Jenkins cache:

```text
/var/jenkins_home/trivy-cache
```

Windows Docker agent cache:

```text
C:\trivy-cache
```

Preload Linux cache:

```powershell
docker exec jenkins sh -c "mkdir -p /var/jenkins_home/trivy-cache && trivy --cache-dir /var/jenkins_home/trivy-cache image --download-db-only && trivy --cache-dir /var/jenkins_home/trivy-cache image --download-java-db-only"
```

Preload Windows cache:

```powershell
New-Item -ItemType Directory -Force -Path C:\trivy-cache | Out-Null
trivy image --cache-dir C:\trivy-cache --download-db-only
trivy image --cache-dir C:\trivy-cache --download-java-db-only
```

The image scan stage uses a Jenkins lock:

```text
trivy-image-cache
```

This prevents parallel image scans from writing to the same Trivy cache at the same time.

## Smoke Tests

Smoke tests are generated from:

```text
apis/<category>/<api-name>/src/main/wso2mi/artifacts/apis/*.xml
```

Jenkins extracts:

```text
API context
resource method
resource uri-template
```

For write methods, Jenkins sends:

```json
{
  "currency": "EUR",
  "amount": 1,
  "customerId": "smoke-test",
  "name": "Smoke Test"
}
```

The smoke test accepts HTTP `2xx` and `3xx`.

## Jenkins Monitoring Dashboard

The Grafana dashboard answers:

```text
Is Jenkins reachable?
Are agents online?
Are executor slots saturated?
Are jobs queued, blocked, or stuck?
Are builds failing or becoming unstable?
Which jobs are slow?
Are Jenkins plugins unhealthy?
```

Main panels:

```text
Jenkins Endpoint
Failed Runs
Success Rate
Queue Waiting
Online Agents
Pipeline Outcomes Over Time
Latest Result By Jenkins Branch/Job
Slowest Jobs By Average Duration
Executor Capacity
Executor Utilization
Free Executor Slots
Queue Health
Agent Online Status
Plugin Health
```

Monitoring is for Jenkins health, not WSO2 business traffic.

## Required Jenkins Credentials

```text
github-token      -> GitHub token for tags/releases
dockerhub-token   -> Docker Hub token for image push
Nexus credentials -> stored in Maven settings.xml
```

## Useful Commands

Check containers:

```powershell
docker ps
```

Check Jenkins metrics:

```powershell
curl.exe http://localhost:8080/prometheus
```

Check Prometheus:

```text
http://localhost:9090/targets
```

Check deployed CAR files:

```powershell
docker exec -it wso2-mi-dev sh -c "ls -lah /home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/"
```

Test POST manually:

```powershell
curl.exe -X POST http://localhost:8290/helloapi/deposit -H "Content-Type: application/json" -d "{\"currency\":\"EUR\",\"amount\":1}"
```

## Notes

Generated WSO2 metadata should not be manually reformatted only for linting. The pipeline validates developer-written files and excludes generated metadata/API definition files.

If no files under `apis/**` changed, Jenkins exits early.
