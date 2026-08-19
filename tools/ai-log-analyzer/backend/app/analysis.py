from __future__ import annotations

from typing import Any

from .ai_service import analyze, analyze_error
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
    errors = payload.get("errors", [])
    primary_error = errors[0] if errors else {}

    if primary_error:
        error_analyses = [analyze_one_error(payload, error, index) for index, error in enumerate(errors)]
        result = dict(error_analyses[0])
        result["error_analyses"] = error_analyses
        result["error_count"] = len(error_analyses)
    else:
        result = analyze(payload)
        result.update({"known_error": False, "occurrence_count": 0, "fingerprint": "", "error_analyses": [], "error_count": 0})

    failure_id = insert_failure(payload, result)
    return {"id": failure_id, "analysis": result}


def analyze_one_error(payload: dict[str, Any], error: dict[str, Any], index: int) -> dict[str, Any]:
    fingerprint, _ = fingerprint_error(error)
    scoped_payload = dict(payload)
    scoped_payload["stage"] = error.get("stage") or payload.get("stage", "unknown")
    scoped_payload["errors"] = [error]

    previous = find_previous_error(fingerprint)
    if previous:
        previous = increment_previous_error(fingerprint) or previous
        result = dict(previous["analysis"])
        result = refresh_generic_known_analysis(result, scoped_payload, fingerprint)
        result.update(
            {
                "known_error": True,
                "occurrence_count": previous["occurrence_count"],
                "fingerprint": fingerprint,
                "previous_solution": previous["solution"],
            }
        )
    else:
        result = analyze_error(scoped_payload, error, index)
        result.update(
            {
                "known_error": False,
                "occurrence_count": 1,
                "fingerprint": fingerprint,
            }
        )
        save_new_previous_error(fingerprint, error.get("type", "Generic"), result)

    result["error_index"] = index
    result["line_start"] = error.get("line_start")
    result["line_end"] = error.get("line_end")
    return result


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
