import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from extract_errors import extract_errors


def test_git_fetch_noise_is_not_dependency_error():
    log = """
[2026-08-18T15:58:06.504Z] Fetching changes from the remote Git repository
[2026-08-18T15:58:06.505Z] Fetching upstream changes from https://github.com/example/repo.git
[Pipeline] sh
"""

    assert extract_errors(log) == []


def test_real_git_fetch_failure_is_detected():
    log = """
[2026-08-18T15:58:06.504Z] Fetching changes from the remote Git repository
[2026-08-18T15:58:16.612Z] ERROR: Error fetching remote repo 'origin'
[2026-08-18T15:58:16.613Z] stderr: fatal: unable to access 'https://github.com/example/repo.git/': Could not resolve host: github.com
"""

    errors = extract_errors(log)

    assert errors
    assert errors[0]["type"] == "Git"
    assert "fatal: unable to access" in errors[0]["context"]


def test_maven_dependency_failure_is_detected():
    log = """
[ERROR] Failed to execute goal on project math: Could not transfer artifact com.example:calculator-lib:jar:1.0.0 from/to wso2-mi-libs-releases
Caused by: Connection refused
"""

    errors = extract_errors(log)

    assert errors
    assert errors[0]["type"] == "Maven"


def test_dependency_download_info_does_not_hide_maven_error():
    log = """
[Pipeline] { (Build CAR: order-api-math)
[INFO] Downloading from allow-local-nexus-http: http://host.docker.internal:8081/repository/wso2-mi-libs-releases/com/example/calculator-lib/1.0.1/calculator-lib-1.0.1.pom
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal on project math: Could not collect dependencies for project com.microintegrator.projects:math:pom:1.0.0
[ERROR] Failed to read artifact descriptor for com.example:calculator-lib:jar:1.0.1
[ERROR] Caused by: The following artifacts could not be resolved: com.example:calculator-lib:pom:1.0.1 (absent): Could not transfer artifact com.example:calculator-lib:pom:1.0.1 from/to allow-local-nexus-http
"""

    errors = extract_errors(log)

    assert errors
    assert errors[0]["message"].startswith("[ERROR]")
    assert "Could not collect dependencies" in errors[0]["message"]
    assert errors[0]["stage"] == "Build CAR: order-api-math"


def test_groovy_file_path_is_not_used_as_stage():
    log = """
Stage 'jenkins/lib/miRuntimeLibs.groovy'
[ERROR] Failed to execute goal on project math: Could not collect dependencies
"""

    errors = extract_errors(log)

    assert errors
    assert errors[0]["stage"] == "unknown"


def test_xml_command_noise_does_not_hide_parser_error():
    log = """
[Pipeline] { (Validate: greeding-api-test)
[2026-08-18T16:03:11.578Z] + find src/main/wso2mi -name *.xml -print0
[2026-08-18T16:03:11.578Z] + xargs -0 -r xmllint --noout
[2026-08-18T16:03:11.578Z] src/main/wso2mi/artifacts/apis/bonjour.xml:7: parser error : Opening and ending tag mismatch: format line 7 and format-broken
[2026-08-18T16:03:11.578Z]                 <format>{message: 'bonjour'}</format-broken>
[2026-08-18T16:03:11.578Z]                                                             ^
"""

    errors = extract_errors(log)

    assert errors
    assert errors[0]["type"] == "XML Validation"
    assert errors[0]["message"].startswith("src/main/wso2mi/artifacts/apis/bonjour.xml:7")
    assert errors[0]["stage"] == "Validate: greeding-api-test"
