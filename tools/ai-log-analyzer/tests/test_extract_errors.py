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


def test_xml_command_noise_does_not_hide_parser_error():
    log = """
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
