from __future__ import annotations

from typing import Any

from .ai_service import analyze
from .database import (
    find_previous_error,
    increment_previous_error,
    insert_failure,
    save_new_previous_error,
    update_previous_error_analysis,
)
from .fingerprint import fingerprint_error, redact


def analyze_and_store(payload: dict[str, Any]) -> dict[str, Any]:
    payload = redact_payload(payload)
    primary_error = payload["errors"][0] if payload["errors"] else {}

    if primary_error:
        fingerprint, _ = fingerprint_error(primary_error)
        previous = find_previous_error(fingerprint)
        if previous:
            previous = increment_previous_error(fingerprint) or previous
            result = dict(previous["analysis"])
            result = refresh_generic_known_analysis(result, payload, fingerprint)
            result.update(
                {
                    "known_error": True,
                    "occurrence_count": previous["occurrence_count"],
                    "fingerprint": fingerprint,
                    "previous_solution": previous["solution"],
                }
            )
        else:
            result = analyze(payload)
            result.update(
                {
                    "known_error": False,
                    "occurrence_count": 1,
                    "fingerprint": fingerprint,
                }
            )
            save_new_previous_error(fingerprint, primary_error.get("type", "Generic"), result)
    else:
        result = analyze(payload)
        result.update({"known_error": False, "occurrence_count": 0, "fingerprint": ""})

    failure_id = insert_failure(payload, result)
    return {"id": failure_id, "analysis": result}


def redact_payload(payload: dict[str, Any]) -> dict[str, Any]:
    redacted = dict(payload)
    errors = []
    for error in payload.get("errors", []):
        clean_error = dict(error)
        clean_error["message"] = redact(str(clean_error.get("message", "")))
        clean_error["context"] = redact(str(clean_error.get("context", "")))
        errors.append(clean_error)
    redacted["errors"] = errors
    return redacted


def refresh_generic_known_analysis(result: dict[str, Any], payload: dict[str, Any], fingerprint: str) -> dict[str, Any]:
    explanation = str(result.get("explanation", ""))
    if "earliest high-signal error block" not in explanation:
        return result

    refreshed = analyze(payload)
    if refreshed.get("explanation") == explanation:
        return result

    update_previous_error_analysis(fingerprint, refreshed)
    return refreshed
