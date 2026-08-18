#!/usr/bin/env python3
"""Extract likely root-cause candidates from Jenkins console logs."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Iterable


ERROR_PATTERNS = [
    (re.compile(r"Could not read Jenkins console log|consoleText fallback .*failed|consoleText fallback did not return", re.I), "Analyzer / Jenkins"),
    (re.compile(r"parser error|Opening and ending tag mismatch|Premature end of data|xmlParse|xmllint", re.I), "XML Validation"),
    (re.compile(r"\[ERROR\]|BUILD FAILURE|Failed to execute goal|Could not (resolve|transfer) artifact", re.I), "Maven"),
    (re.compile(r"fatal: unable to access|error fetching remote repo|maximum checkout retry attempts reached|Could not resolve host: github\.com", re.I), "Git"),
    (re.compile(r"trivy|vulnerabilit|security .*failed|CRITICAL|HIGH", re.I), "Security"),
    (re.compile(r"gitleaks|secret", re.I), "Secrets"),
    (re.compile(r"docker .*failed|Cannot connect to the Docker daemon|denied:|unauthorized", re.I), "Docker"),
    (re.compile(r"nexus|Could not (resolve|transfer) artifact|Failed to collect dependencies|dependency resolution", re.I), "Dependency / Nexus"),
    (re.compile(r"micro integrator|heartbeat|ICP|PKIX|SSLHandshake", re.I), "WSO2 MI"),
    (re.compile(r"\b(400|401|403|404|500|502|503)\b|Bad Gateway|Unauthorized|Forbidden", re.I), "HTTP"),
    (re.compile(r"Exception|Caused by:|Traceback|script returned exit code|ERROR|FAILURE|failed", re.I), "Generic"),
    (re.compile(r"Could not resolve host|connection refused|timed out|unexpected eof|TLS connect error", re.I), "Network"),
]

NOISE_PATTERNS = [
    re.compile(r"^\[Pipeline\]"),
    re.compile(r"^\s*$"),
    re.compile(r"^(?:\[[^\]]+\]\s*)?\+\s+"),
    re.compile(r"Fetching changes from the remote Git repository", re.I),
    re.compile(r"Fetching upstream changes from", re.I),
    re.compile(r"using credential", re.I),
    re.compile(r"The recommended git tool is:", re.I),
]

SECRET_PATTERNS = [
    re.compile(r"(?i)(Authorization:\s*Bearer\s+)[A-Za-z0-9._\-]+"),
    re.compile(r"(?i)(password\s*[=:]\s*)['\"]?[^'\"\s]+"),
    re.compile(r"(?i)(token\s*[=:]\s*)['\"]?[^'\"\s]+"),
    re.compile(r"(?i)(secret\s*[=:]\s*)['\"]?[^'\"\s]+"),
    re.compile(r"(?i)(GIT_TOKEN=)[^\s]+"),
]


def redact(text: str) -> str:
    redacted = text
    for pattern in SECRET_PATTERNS:
        redacted = pattern.sub(r"\1[REDACTED]", redacted)
    return redacted


def clean_line(line: str) -> str:
    line = re.sub(r"^\d{2}:\d{2}:\d{2}\s+", "", line)
    line = re.sub(r"^\[\d{4}-\d{2}-\d{2}T[^\]]+\]\s+", "", line)
    return redact(line.rstrip())


def is_noise(line: str) -> bool:
    return any(pattern.search(line) for pattern in NOISE_PATTERNS)


def classify(line: str) -> str:
    for pattern, error_type in ERROR_PATTERNS:
        if pattern.search(line):
            return error_type
    return "Generic"


def matching_lines(lines: list[str]) -> Iterable[tuple[int, str]]:
    for index, line in enumerate(lines):
        if is_noise(line):
            continue
        if any(pattern.search(line) for pattern, _ in ERROR_PATTERNS):
            yield index, classify(line)


def merge_windows(windows: list[tuple[int, int, str]]) -> list[tuple[int, int, str]]:
    if not windows:
        return []

    windows.sort(key=lambda item: item[0])
    merged: list[tuple[int, int, str]] = [windows[0]]
    for start, end, error_type in windows[1:]:
        last_start, last_end, last_type = merged[-1]
        if start <= last_end:
            merged[-1] = (last_start, max(last_end, end), last_type if last_type != "Generic" else error_type)
        else:
            merged.append((start, end, error_type))
    return merged


def extract_errors(log_text: str, before: int = 15, after: int = 15, max_errors: int = 8) -> list[dict]:
    raw_lines = log_text.splitlines()
    lines = [clean_line(line) for line in raw_lines]
    windows = []

    for index, error_type in matching_lines(lines):
        start = max(0, index - before)
        end = min(len(lines), index + after + 1)
        windows.append((start, end, error_type))

    errors = []
    seen = set()
    for start, end, error_type in merge_windows(windows):
        context_lines = [line for line in lines[start:end] if not is_noise(line)]
        context = "\n".join(context_lines).strip()
        if not context:
            continue
        digest = hashlib.sha256(context.encode("utf-8")).hexdigest()
        if digest in seen:
            continue
        seen.add(digest)
        first_message = next(
            (
                line
                for line in context_lines
                if any(pattern.search(line) for pattern, _ in ERROR_PATTERNS)
            ),
            next((line for line in context_lines if line.strip()), "Unknown failure"),
        )
        errors.append(
            {
                "type": error_type,
                "message": first_message[:300],
                "line_start": start + 1,
                "line_end": end,
                "context": context[:6000],
            }
        )
        if len(errors) >= max_errors:
            break

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--pipeline", default="")
    parser.add_argument("--build-number", default="")
    parser.add_argument("--branch", default="")
    parser.add_argument("--commit", default="")
    parser.add_argument("--status", default="FAILED")
    parser.add_argument("--stage", default="unknown")
    args = parser.parse_args()

    log_text = Path(args.input).read_text(encoding="utf-8", errors="replace")
    payload = {
        "pipeline": args.pipeline,
        "build_number": args.build_number,
        "branch": args.branch,
        "commit": args.commit,
        "stage": args.stage,
        "status": args.status,
        "errors": extract_errors(log_text),
    }
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
