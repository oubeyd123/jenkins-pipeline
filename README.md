# WSO2 MI CI/CD Pipeline

This repository contains a Jenkins Multibranch CI/CD pipeline for WSO2 Micro Integrator 4.6.0 projects.

The pipeline detects changed APIs, validates only those APIs, builds CAR files, resolves custom runtime JARs from Nexus, builds WSO2 MI Docker images, scans the source and images, deploys runtime containers, runs smoke tests, creates GitHub releases, and exposes Jenkins metrics to Prometheus and Grafana.

## Project Structure

```text
apis/
  <api-category>/
    <api-name>/
      src/main/wso2mi/
      deployment/docker/Dockerfile
      pom.xml

jenkins/lib/
  Groovy helper files used by Jenkinsfile

monitoring/grafana/
  Grafana dashboard JSON files

Dockerfile.dev
Jenkinsfile
.yamllint
```

Each API must be placed under:

```text
apis/<category>/<api-name>
```

Example:

```text
apis/order-api/product-api
```

The pipeline converts that path into a slug:

```text
order-api-product-api
```

The slug is used for Jenkins stages, Docker image names, Git tags, GitHub releases, and security report names.

## Branch Workflow

The expected promotion flow is:

```text
feature/* -> develop -> main
```

Branch behavior:

```text
feature/* and PRs -> validate only
develop           -> integration container deployment and smoke test
main              -> official release, versioned image, deployment, smoke test
```

Developers push work to feature branches, open a pull request into `develop`, then promote stable work from `develop` into `main`.

## Pipeline Behavior

### Feature Branches and Pull Requests

Feature branches are used for fast validation.

The pipeline:

```text
detects changed APIs
runs quality checks
runs Gitleaks
runs Trivy filesystem scan
builds WSO2 CAR files
checks Docker image build
does not deploy
does not push Docker images
does not create releases
```

### Develop Branch

The `develop` branch validates changed APIs in a real WSO2 MI Docker runtime.

The pipeline:

```text
detects changed APIs
runs quality checks
checks duplicate WSO2 API contexts
runs Gitleaks
runs Trivy filesystem scan
builds changed CAR files
downloads required runtime JARs from Nexus
builds and pushes one dev WSO2 MI image
runs Trivy image scan
deploys the wso2-mi-dev container
runs method-aware smoke tests
```

The develop image is built from `Dockerfile.dev`. It contains all CAR files changed in the develop push plus any runtime JARs needed by those APIs.

### Main Branch

The `main` branch creates official release outputs.

The pipeline:

```text
detects changed APIs
runs quality and security checks
builds CAR files
downloads required runtime JARs from Nexus
calculates the next version
creates a Git tag
creates a GitHub Release
builds and pushes a versioned Docker image
runs Trivy image scan
deploys a release container
runs method-aware smoke tests
```

Release example:

```text
Git tag:      order-api-product-api-v0.1.0
Release name: order-api-product-api v0.1.0
Docker image: docker.io/oubeyd/order-api-product-api:v0.1.0-fa78fa3a
```

## Jenkins Agents

This project uses two Jenkins execution environments.

### Linux Jenkins Agent

Usually the built-in Jenkins container agent.

Used for:

```text
checkout
changed API detection
quality checks
Maven CAR build
Nexus dependency resolution
Gitleaks source scanning
Trivy filesystem scanning
version calculation
Git tags and GitHub releases
```

Required tools:

```text
git
maven
java
xmllint
yamllint
gitleaks
trivy
```

### Windows Docker Agent

The local Windows Jenkins agent, labelled:

```text
wso2-dev-server
```

Used for:

```text
Docker build
Docker push
Trivy image scan
container deployment
smoke tests against localhost
```

Required tools:

```powershell
docker --version
trivy --version
java --version
mvn --version
```

The Windows agent must be connected before running develop or main deployments.

## Required Containers

The project needs these containers for the full local CI/CD environment:

```text
jenkins     -> Jenkins server
nexus       -> Maven repository for custom runtime JARs
prometheus  -> collects Jenkins metrics
grafana     -> visual dashboard for Jenkins pipeline metrics
wso2-mi-dev -> created by Jenkins during develop deployments
```

The `wso2-mi-dev` container is not started manually during normal pipeline usage. Jenkins creates it during the develop branch deployment stage.

## Jenkins Container

If Jenkins is already running, check it with:

```powershell
docker ps
```

Typical Jenkins container command:

```powershell
docker volume create jenkins_home

docker run -d --name jenkins `
  -p 8080:8080 `
  -p 50000:50000 `
  -v jenkins_home:/var/jenkins_home `
  jenkins/jenkins:lts
```

Open Jenkins:

```text
http://localhost:8080
```

The GitHub webhook URL is:

```text
http://<jenkins-url>/github-webhook/
```

When Jenkins runs locally and GitHub must reach it, use ngrok:

```powershell
ngrok http 8080
```

Then configure GitHub webhook with:

```text
https://<ngrok-domain>/github-webhook/
```

Use webhook events:

```text
push
pull_request
```

## Nexus Container

Nexus is used as a Maven repository for custom JAR files required by WSO2 MI runtime.

Run Nexus:

```powershell
docker volume create nexus-data

docker run -d --name nexus `
  -p 8081:8081 `
  -v nexus-data:/nexus-data `
  sonatype/nexus3:latest
```

Open Nexus:

```text
http://localhost:8081
```

Recommended repositories:

```text
wso2-mi-libs-releases  -> Maven hosted repository for release JARs
wso2-mi-libs-snapshots -> Maven hosted repository for snapshot JARs
```

The Jenkins Maven settings file must contain credentials for these repository IDs:

```text
/var/jenkins_home/.m2/settings.xml
```

The API `pom.xml` declares custom runtime libraries in the `mi-runtime-libs` profile. Jenkins runs that profile and copies the resolved JARs into the WSO2 MI Docker image.

## Custom MI Runtime Libraries

Use custom runtime libraries when an API needs a JAR inside the WSO2 MI runtime `/lib` directory.

Common examples:

```text
custom mediators
JDBC drivers
third-party SDKs
shared Java utility libraries
```

The developer declares the required JAR in the API `pom.xml`:

```xml
<profile>
  <id>mi-runtime-libs</id>
  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>calculator-lib</artifactId>
      <version>1.0.1</version>
    </dependency>
  </dependencies>
</profile>
```

Jenkins downloads the JAR into:

```text
apis/<category>/<api-name>/target/mi-runtime-libs/
```

Then Docker copies it into:

```text
/home/wso2carbon/wso2mi-4.6.0/lib/
```

This makes the JAR available to the API when the WSO2 MI container starts.

## WSO2 MI Docker Images

The Docker images are based on:

```text
wso2/wso2mi:4.6.0
```

CAR files are copied into:

```text
/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/
```

Runtime JARs are copied into:

```text
/home/wso2carbon/wso2mi-4.6.0/lib/
```

When the container starts, WSO2 MI automatically deploys the CAR files.

### Develop Image

Develop builds one integration image:

```text
docker.io/oubeyd/wso2-mi-dev:dev-<build-number>-<commit-sha>
```

That image is deployed as:

```text
wso2-mi-dev
```

Fixed ports:

```text
8290 -> HTTP APIs
8253 -> HTTPS APIs
9164 -> WSO2 internal HTTPS listener
```

### Release Images

Main builds one image per released API:

```text
docker.io/oubeyd/<api-slug>:v<version>-<commit-sha>
docker.io/oubeyd/<api-slug>:latest
```

Release containers may use dynamic host ports to avoid conflicts with the develop container.

## Manual Docker Build Check

The pipeline normally performs this automatically.

For manual testing inside one API folder:

```powershell
cd apis\order-api\product-api

New-Item -ItemType Directory -Force -Path CompositeApps | Out-Null
New-Item -ItemType Directory -Force -Path resources | Out-Null
New-Item -ItemType Directory -Force -Path libs | Out-Null
Copy-Item -Path target\*.car -Destination CompositeApps\ -Force

docker build `
  --build-arg BASE_IMAGE=wso2/wso2mi:4.6.0 `
  --build-arg WSO2_SERVER_HOME=/home/wso2carbon/wso2mi-4.6.0 `
  -f deployment/docker/Dockerfile `
  -t local/order-api-product-api:test .
```

## Smoke Testing

Smoke tests are handled by:

```text
jenkins/lib/smokeTest.groovy
```

Jenkins reads the WSO2 API XML files:

```text
apis/<category>/<api-name>/src/main/wso2mi/artifacts/apis/*.xml
```

It extracts:

```text
API context
resource method
resource uri-template
```

Example:

```xml
<api context="/customer" name="CustomerAPI">
  <resource methods="POST" uri-template="/">
```

Smoke target:

```text
POST http://localhost:8290/customer
```

For methods that need a body, Jenkins sends a basic JSON payload:

```json
{}
```

The smoke test passes for:

```text
2xx
3xx
405
```

`405 Method Not Allowed` can mean the API is deployed but the endpoint requires a different method. The current smoke logic is method-aware to avoid false failures caused by sending GET to POST endpoints.

## Runtime API Context Validation

This check is handled by:

```text
jenkins/lib/runtimeChecks.groovy
```

It prevents multiple APIs deployed into the same MI container from using the same context.

Example duplicate context:

```text
/helloapi
```

If two APIs use the same context, WSO2 MI can route requests incorrectly or one API can override the other. The pipeline fails early before deployment.

## Quality Checks

Quality checks are handled by:

```text
jenkins/lib/qualityChecks.groovy
```

Checks:

```text
mvn validate
xmllint for XML syntax
yamllint for YAML syntax/style
```

WSO2 Integration Studio generates YAML files that may not follow strict `yamllint` formatting rules. The pipeline keeps linting developer-written YAML and excludes generated WSO2 metadata/API definition files.

## Security Scans

Security checks are handled by:

```text
jenkins/lib/securityChecks.groovy
```

Tools:

```text
Gitleaks  -> source secret scanning
Trivy FS  -> source dependency and configuration scanning
Trivy image -> Docker image vulnerability scanning
```

Filesystem scan policy:

```text
Gitleaks secrets fail the pipeline
Trivy HIGH and CRITICAL findings fail the pipeline
```

Image scan policy:

```text
Trivy CRITICAL findings fail the pipeline
HIGH findings are reported
```

Security reports are generated as Markdown and archived in Jenkins:

```text
target/security-reports/
```

Example report:

```text
target/security-reports/order-api-math-filesystem-security-report.md
```

## Trivy Cache

Trivy uses a persistent cache to avoid downloading vulnerability databases every run.

Linux Jenkins agent:

```text
/var/jenkins_home/trivy-cache
```

Windows Docker agent:

```text
C:\trivy-cache
```

Preload the Linux cache inside the Jenkins container:

```powershell
docker exec jenkins sh -c "mkdir -p /var/jenkins_home/trivy-cache && trivy --cache-dir /var/jenkins_home/trivy-cache image --download-db-only && trivy --cache-dir /var/jenkins_home/trivy-cache image --download-java-db-only"
```

Preload the Windows cache:

```powershell
New-Item -ItemType Directory -Force -Path C:\trivy-cache | Out-Null
trivy image --cache-dir C:\trivy-cache --download-db-only
trivy image --cache-dir C:\trivy-cache --download-java-db-only
```

Copy Trivy cache from Jenkins container to Windows:

```powershell
New-Item -ItemType Directory -Force -Path C:\trivy-cache | Out-Null
docker cp jenkins:/var/jenkins_home/trivy-cache/. C:\trivy-cache
```

## Versioning and Releases

Versioning is handled by:

```text
jenkins/lib/Versioning.groovy
```

Commit message rules:

```text
fix:               patch version
feat:              minor version
!:                 major version
feat!:             major version
BREAKING CHANGE:   major version
```

Examples:

```text
fix: update API      -> 0.0.1 to 0.0.2
feat: add endpoint   -> 0.0.1 to 0.1.0
!: change API shape  -> 0.0.1 to 1.0.0
```

GitHub releases include:

```text
API name
API path
Version
Commit SHA
Changes included
```

## Email Failure Notification

The pipeline sends an email when a build fails if this variable is configured:

```text
FAILURE_EMAIL_RECIPIENTS
```

Current location:

```text
Jenkinsfile environment block
```

Jenkins must have the Email Extension plugin configured with SMTP settings.

The failure email contains:

```text
job name
build number
branch
commit
Jenkins build URL
compressed console log
```

## Prometheus

Jenkins exposes metrics through the Prometheus plugin.

Jenkins metrics endpoint:

```text
http://localhost:8080/prometheus
```

Prometheus config file:

```text
C:\tmp\prometheus.yml
```

Example:

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

Run Prometheus:

```powershell
docker volume create prometheus-data

docker run -d --name prometheus `
  -p 9090:9090 `
  -v C:\tmp\prometheus.yml:/etc/prometheus/prometheus.yml:ro `
  -v prometheus-data:/prometheus `
  prom/prometheus:latest
```

Open Prometheus:

```text
http://localhost:9090
```

Check targets:

```text
http://localhost:9090/targets
```

The Jenkins target should be `UP`.

## Grafana

Grafana displays Jenkins pipeline metrics from Prometheus.

Run Grafana:

```powershell
docker volume create grafana-data

docker run -d --name grafana `
  -p 3000:3000 `
  -v grafana-data:/var/lib/grafana `
  grafana/grafana:latest
```

Open Grafana:

```text
http://localhost:3000
```

Default login:

```text
admin / admin
```

Add Prometheus as a data source:

```text
http://host.docker.internal:9090
```

Import the custom dashboard:

```text
monitoring/grafana/wso2-mi-jenkins-dashboard.json
```

The dashboard shows:

```text
Jenkins status
failed pipeline runs
queue size
online Jenkins agents
pipeline result trend
average build duration
executor usage by agent
queue pressure
plugin health
```

## Required Jenkins Credentials

Credentials must be stored in Jenkins Credentials, not in source code.

Required credentials:

```text
GIT_CRED_ID      -> GitHub token for tags and releases
REGISTRY_CRED_ID -> Docker Hub token for image push
Nexus credentials -> Maven settings.xml for custom runtime JAR download
```

## Useful Commands

Check running containers:

```powershell
docker ps
```

Check the dev MI container:

```powershell
docker logs wso2-mi-dev
```

Open a shell inside the dev MI container:

```powershell
docker exec -it wso2-mi-dev sh
```

Check deployed CAR files:

```powershell
docker exec -it wso2-mi-dev sh -c "ls -lah /home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/"
```

Test an API manually:

```powershell
curl.exe http://localhost:8290/helloapi
```

Test a POST API manually:

```powershell
curl.exe -X POST http://localhost:8290/customer -H "Content-Type: application/json" -d "{}"
```

Check Jenkins metrics:

```powershell
curl.exe http://localhost:8080/prometheus
```

Check Prometheus targets:

```text
http://localhost:9090/targets
```

## Notes

Generated WSO2 files should not be manually reformatted only to satisfy a generic linter. The pipeline is configured to validate developer-written files while avoiding unnecessary failures caused by generated WSO2 metadata.

The pipeline only processes APIs changed in the current Git range. If no files under `apis/**` changed, Jenkins exits early.
