from __future__ import annotations

import hashlib
import re
from typing import Any


SECRET_PATTERNS = [
    re.compile(r"(?i)(Authorization:\s*Bearer\s+)[A-Za-z0-9._\-]+"),
    re.compile(r"(?i)(password\s*[=:]\s*)['\"]?[^'\"\s]+"),
    re.compile(r"(?i)(token\s*[=:]\s*)['\"]?[^'\"\s]+"),
    re.compile(r"(?i)(secret\s*[=:]\s*)['\"]?[^'\"\s]+"),
    re.compile(r"(?i)(GIT_TOKEN=)[^\s]+"),
]

VOLATILE_PATTERNS = [
    re.compile(r"\b[a-z0-9_.-]+:[a-z0-9_.-]+(?::[a-z0-9_.-]+)?\b", re.I),
    re.compile(r"\b[a-z0-9_.-]+-v?\d+\.\d+\.\d+(?:-[a-z0-9_.-]+)?\b", re.I),
    re.compile(r"\b[0-9a-f]{7,40}\b", re.I),
    re.compile(r"\b\d{2}:\d{2}:\d{2}\b"),
    re.compile(r"\b\d{4}-\d{2}-\d{2}[T ][0-9:.+\-Z]+\b"),
    re.compile(r"\bbuild\s*#?\d+\b", re.I),
    re.compile(r"\bworkspace[/\\][^\s]+", re.I),
    re.compile(r"\b[A-Za-z]:\\[^\s]+"),
    re.compile(r"\bv?\d+\.\d+\.\d+(?:[-+][A-Za-z0-9_.-]+)?\b"),
    re.compile(r"\b\d+\b"),
]


def redact(text: str) -> str:
    redacted = text
    for pattern in SECRET_PATTERNS:
        redacted = pattern.sub(r"\1[REDACTED]", redacted)
    return redacted


def normalize_error_text(error: dict[str, Any]) -> str:
    raw = "\n".join(
        [
            str(error.get("type", "")),
            str(error.get("message", "")),
            str(error.get("context", "")),
        ]
    )
    text = redact(raw).lower()
    for pattern in VOLATILE_PATTERNS:
        text = pattern.sub("[var]", text)
    text = re.sub(r"['\"`]", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def fingerprint_error(error: dict[str, Any]) -> tuple[str, str]:
    normalized = normalize_error_text(error)
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    return digest, normalized
