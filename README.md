# WSO2 MI CI/CD Pipeline

This repository contains a Jenkins Multibranch CI/CD pipeline for WSO2 Micro Integrator (MI) API projects.

The pipeline builds WSO2 CAR files, runs quality and security checks, creates Docker images, deploys WSO2 MI containers, runs smoke tests, and creates versioned releases from the `main` branch.

## Project Structure

```text
apis/
  <api-category>/
    <api-name>/
      src/main/wso2mi/
      deployment/docker/Dockerfile
      pom.xml

jenkins/lib/
  Groovy helper scripts used by Jenkinsfile

Dockerfile.dev
Jenkinsfile
.yamllint
```

Each API is stored under:

```text
apis/<category>/<api-name>
```

Example:

```text
apis/order-api/product-api
```

The pipeline converts this path into a slug:

```text
order-api-product-api
```

That slug is used for Jenkins stages, Docker images, Git tags, and releases.

## Branch Workflow

The expected promotion flow is:

```text
feature/* -> develop -> main
```

Recommended usage:

```text
feature/* = development work
develop   = integration and dev runtime testing
main      = release branch
```

Developers should push work to feature branches, open Pull Requests into `develop`, then promote stable work from `develop` into `main`.

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
does not run Trivy image scan
```

### Develop Branch

The `develop` branch validates APIs in a real WSO2 MI Docker runtime.

The pipeline:

```text
detects changed APIs
runs quality checks
checks duplicate runtime API contexts
runs Gitleaks
runs Trivy filesystem scan
builds changed CAR files
builds and pushes a dev WSO2 MI Docker image
runs Trivy image scan
deploys the wso2-mi-dev container
runs smoke tests against localhost:8290
```

The dev container uses fixed ports:

```text
8290 -> HTTP APIs
8253 -> HTTPS APIs
9164 -> WSO2 internal HTTPS listener
```

### Main Branch

The `main` branch creates official releases.

The pipeline:

```text
detects changed APIs
runs quality and security checks
builds CAR files
calculates the next version
creates a Git tag
creates a GitHub Release
builds and pushes a versioned Docker image
runs Trivy image scan
deploys a release container
runs smoke tests
```

Release example:

```text
Git tag:      order-api-product-api-v0.0.1
Release name: order-api-product-api v0.0.1
Docker image: docker.io/oubeyd/order-api-product-api:v0.0.1-fa78fa3a
```

## Changed API Detection

The pipeline does not build every API on every run. It checks Git changes under:

```text
apis/**
```

Only changed API projects are validated, packaged, scanned, deployed, or released.

## Quality Checks

Quality checks are handled by:

```text
jenkins/lib/qualityChecks.groovy
```

They include:

```text
mvn validate
XML syntax validation with xmllint
YAML linting with yamllint
```

WSO2 Integration Studio generates YAML files that may not follow `yamllint` style rules. The pipeline keeps linting developer-written YAML while excluding generated WSO2 metadata and API definition YAML files.

## Security Checks

Security checks are handled by:

```text
jenkins/lib/securityChecks.groovy
```

Tools used:

```text
Gitleaks -> secret detection
Trivy fs -> filesystem, dependency, secret, and config scanning
Trivy image -> Docker image vulnerability scanning
```

Current image policy:

```text
HIGH findings are shown in Jenkins logs
CRITICAL findings fail the pipeline
```

Trivy uses a persistent cache to avoid downloading databases every run:

```text
Windows agent: C:\trivy-cache
Unix agent:   .trivy-cache
```

## Docker Runtime

The pipeline uses Docker to run WSO2 MI.

For `develop`, Jenkins builds one dev image using:

```text
Dockerfile.dev
```

The Dockerfile copies CAR files into the WSO2 MI deployment folder:

```text
/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/
```

When the container starts, WSO2 MI automatically deploys the CAR files from that folder.

## Custom MI Runtime Libraries

This project uses Nexus and Maven to manage JAR files required by the WSO2 MI runtime `/lib` directory.

Nexus stores the JAR files, Maven downloads the required versions, and Jenkins copies them into the Docker image during the build.

Use the `mi-runtime-libs` Maven profile in the API `pom.xml` when an API needs runtime JARs such as JDBC drivers, custom mediators, validators, or third-party SDKs.

```xml
<profile>
  <id>mi-runtime-libs</id>
  <dependencies>
    <dependency>
      <groupId>com.company.wso2</groupId>
      <artifactId>custom-mediator</artifactId>
      <version>1.0.0</version>
    </dependency>

    <dependency>
      <groupId>mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <version>8.4.0</version>
    </dependency>
  </dependencies>
</profile>
```

During the pipeline, Jenkins resolves the dependencies and stores them in:

```text
apis/<category>/<api-name>/target/mi-runtime-libs/
```

Then Docker copies them into the WSO2 MI runtime:

```text
/home/wso2carbon/wso2mi-4.6.0/lib/
```

## Smoke Testing

Smoke tests are handled by:

```text
jenkins/lib/smokeTest.groovy
```

Jenkins extracts API contexts from WSO2 API XML files:

```xml
<api context="/helloapi" name="helloApi">
```

Then it calls:

```text
base URL + API context
```

Example for `develop`:

```text
http://localhost:8290/helloapi
```

Smoke test passes when the response status starts with:

```text
2xx or 3xx
```

The smoke test retries while WSO2 MI starts and deploys CAR files:

```text
24 attempts
5 seconds between attempts
```

If a smoke test fails, Jenkins prints Docker logs from the MI container.

## Versioning and Releases

Versioning is handled by:

```text
jenkins/lib/Versioning.groovy
```

The pipeline reads commit messages and applies semantic versioning:

```text
fix:      patch version
feat:     minor version
!:        major version
feat!:    major version
BREAKING CHANGE: major version
```

Example:

```text
fix: update API       -> 0.0.1 to 0.0.2
feat: add endpoint    -> 0.0.1 to 0.1.0
!: change API shape   -> 0.0.1 to 1.0.0
```

GitHub Release body includes:

```text
API name
API path
Version
Commit SHA
Changes included
```

## Jenkins Agent Requirements

The Windows Jenkins agent used for Docker deployment and smoke testing must have:

```text
Docker Desktop
Trivy
Gitleaks
Java
Maven
```

The Jenkins agent should be restarted after installing new tools or modifying `PATH`.

## Jenkins Credentials

Required Jenkins credentials:

```text
GitHub token -> tags and GitHub Releases
Docker Hub token -> Docker image push
```

Credentials must be stored in Jenkins Credentials, not in source code.

## Useful Commands

Check required tools on the Windows agent:

```powershell
docker --version
trivy --version
gitleaks version
mvn --version
java --version
```

Preload Trivy cache:

```powershell
mkdir C:\trivy-cache -Force
trivy image --download-db-only --cache-dir C:\trivy-cache
trivy image --download-java-db-only --cache-dir C:\trivy-cache
```

Check the dev container:

```powershell
docker ps
docker logs wso2-mi-dev
```

Test an API manually:

```powershell
curl.exe http://localhost:8290/helloapi
```
