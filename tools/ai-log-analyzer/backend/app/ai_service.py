from __future__ import annotations

from typing import Any


CATEGORY_ACTIONS = {
    "Dependency / Nexus": [
        "Check that Nexus is running and reachable from Jenkins.",
        "Verify Maven settings.xml repository credentials.",
        "Retry after Nexus responds successfully.",
    ],
    "Docker": [
        "Check Docker Desktop or the Docker daemon on the deployment agent.",
        "Verify image name, Dockerfile path, and registry credentials.",
        "Inspect the failing docker command in the Jenkins log.",
    ],
    "Git": [
        "Check network/DNS connectivity from the Jenkins controller or agent to GitHub.",
        "Verify the Jenkins GitHub credential still has access to the repository.",
        "Retry after GitHub connectivity works from inside the Jenkins container.",
    ],
    "Security": [
        "Open the archived security report from Jenkins.",
        "Check whether the finding is a real vulnerability or a policy failure.",
        "Update the dependency/image or adjust policy only if it is a false positive.",
    ],
    "Secrets": [
        "Remove the detected secret from source control.",
        "Move secrets to Jenkins Credentials.",
        "Rotate the secret if it was committed.",
    ],
    "WSO2 MI": [
        "Check the MI container logs.",
        "Verify deployment.toml, CAR deployment, ICP config, and certificates.",
        "Confirm the container is on the expected Docker network.",
    ],
    "Network": [
        "Check DNS/network connectivity from the Jenkins agent.",
        "Retry if the error is transient.",
        "Verify proxy, Docker network, and external service availability.",
    ],
    "Analyzer / Jenkins": [
        "Check whether Jenkins allowed the pipeline to read the console log.",
        "Open the archived target/ai-failure files to see what was captured.",
        "Approve the required Jenkins script method or enable the consoleText fallback.",
    ],
    "XML Validation": [
        "Open the XML file and go to the line reported by xmllint.",
        "Fix the mismatched, missing, or malformed XML tag.",
        "Run xmllint locally or rerun the Jenkins validation stage.",
    ],
}


def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    """Initial provider: deterministic analysis with AI-compatible JSON shape.

    This keeps the system useful without exposing an API key. A real provider can
    replace this function later without changing Jenkins or the Chrome extension.
    """
    errors = payload.get("errors") or []
    primary = errors[0] if errors else {}
    category = primary.get("type", "Generic")
    message = primary.get("message", "Pipeline failed, but no clear error block was extracted.")
    stage = payload.get("stage") or "unknown"

    actions = CATEGORY_ACTIONS.get(
        category,
        [
            "Open the extracted error block and identify the first failing command.",
            "Check the service mentioned nearest to the first error.",
            "Retry only after the root service issue is fixed.",
        ],
    )

    return {
        "summary": f"{payload.get('pipeline', 'Pipeline')} failed: {message}",
        "root_cause": infer_root_cause(category, message),
        "category": category,
        "stage": stage,
        "confidence": 0.72 if errors else 0.35,
        "explanation": "The analyzer selected the earliest high-signal error block and classified it by tool-specific patterns.",
        "suggested_actions": actions,
    }


def infer_root_cause(category: str, message: str) -> str:
    lower = message.lower()
    if "could not resolve host" in lower:
        return "DNS resolution failed while Jenkins was connecting to an external or container service."
    if "connection refused" in lower:
        return "The target service was reachable by name, but nothing was listening on the requested port."
    if "unexpected eof" in lower or "tls connect" in lower:
        return "A network or TLS connection was interrupted during an external service request."
    if category == "Dependency / Nexus":
        return "Maven could not retrieve a dependency from the configured repository."
    if category == "Git":
        return "Jenkins failed while fetching source code from Git."
    if category == "XML Validation":
        return "An XML file failed validation, usually because a tag is malformed or not closed correctly."
    if category == "Secrets":
        return "A secret scanner detected sensitive-looking content in the source tree."
    return message
