import json
import os
import sqlite3
from pathlib import Path
from typing import Any


def db_path() -> Path:
    database_url = os.environ.get("AI_ANALYZER_DATABASE_URL", "sqlite:///./data/failures.db")
    if not database_url.startswith("sqlite:///"):
        raise ValueError("Only sqlite:/// URLs are supported by the initial backend")
    path = Path(database_url.replace("sqlite:///", "", 1))
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(db_path())
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS failures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pipeline TEXT NOT NULL,
                build_number TEXT NOT NULL,
                branch TEXT,
                commit_sha TEXT,
                stage TEXT,
                status TEXT,
                extracted_error TEXT NOT NULL,
                ai_analysis TEXT NOT NULL,
                root_cause TEXT,
                suggested_actions TEXT,
                fingerprint TEXT,
                known_error INTEGER NOT NULL DEFAULT 0,
                occurrence_count INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        )
        ensure_column(conn, "failures", "fingerprint", "TEXT")
        ensure_column(conn, "failures", "known_error", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(conn, "failures", "occurrence_count", "INTEGER NOT NULL DEFAULT 1")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS previous_errors (
                fingerprint TEXT PRIMARY KEY,
                error_type TEXT NOT NULL,
                root_cause TEXT NOT NULL,
                solution TEXT NOT NULL,
                analysis TEXT NOT NULL,
                occurrence_count INTEGER NOT NULL DEFAULT 1,
                first_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        )


def ensure_column(conn: sqlite3.Connection, table: str, column: str, definition: str) -> None:
    columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
    if column not in columns:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")


def insert_failure(payload: dict[str, Any], analysis: dict[str, Any]) -> int:
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO failures (
                pipeline, build_number, branch, commit_sha, stage, status,
                extracted_error, ai_analysis, root_cause, suggested_actions,
                fingerprint, known_error, occurrence_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                payload.get("pipeline", ""),
                payload.get("build_number", ""),
                payload.get("branch", ""),
                payload.get("commit", ""),
                analysis.get("stage") or payload.get("stage", ""),
                payload.get("status", "FAILED"),
                json.dumps(payload),
                json.dumps(analysis),
                analysis.get("root_cause", ""),
                json.dumps(analysis.get("suggested_actions", [])),
                analysis.get("fingerprint", ""),
                1 if analysis.get("known_error") else 0,
                analysis.get("occurrence_count", 1),
            ),
        )
        return int(cursor.lastrowid)


def find_previous_error(fingerprint: str) -> dict[str, Any] | None:
    with connect() as conn:
        row = conn.execute(
            "SELECT * FROM previous_errors WHERE fingerprint = ?",
            (fingerprint,),
        ).fetchone()
    return row_to_previous_error(row) if row else None


def save_new_previous_error(fingerprint: str, error_type: str, analysis: dict[str, Any]) -> None:
    solution = "\n".join(analysis.get("suggested_actions", []))
    with connect() as conn:
        conn.execute(
            """
            INSERT OR IGNORE INTO previous_errors (
                fingerprint, error_type, root_cause, solution, analysis, occurrence_count
            ) VALUES (?, ?, ?, ?, ?, 1)
            """,
            (
                fingerprint,
                error_type,
                analysis.get("root_cause", ""),
                solution,
                json.dumps(analysis),
            ),
        )


def increment_previous_error(fingerprint: str) -> dict[str, Any] | None:
    with connect() as conn:
        conn.execute(
            """
            UPDATE previous_errors
            SET occurrence_count = occurrence_count + 1,
                last_seen = CURRENT_TIMESTAMP
            WHERE fingerprint = ?
            """,
            (fingerprint,),
        )
        row = conn.execute(
            "SELECT * FROM previous_errors WHERE fingerprint = ?",
            (fingerprint,),
        ).fetchone()
    return row_to_previous_error(row) if row else None


def row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    data = dict(row)
    data["extracted_error"] = json.loads(data["extracted_error"])
    data["ai_analysis"] = json.loads(data["ai_analysis"])
    data["suggested_actions"] = json.loads(data["suggested_actions"] or "[]")
    data["known_error"] = bool(data.get("known_error"))
    return data


def row_to_previous_error(row: sqlite3.Row) -> dict[str, Any]:
    data = dict(row)
    data["analysis"] = json.loads(data["analysis"])
    return data
