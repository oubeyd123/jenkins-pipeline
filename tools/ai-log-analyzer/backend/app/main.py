from __future__ import annotations

from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .analysis import analyze_and_store
from .database import (
    connect,
    init_db,
    row_to_dict,
)


class ErrorBlock(BaseModel):
    type: str
    message: str
    context: str
    line_start: int | None = None
    line_end: int | None = None
    stage: str = "unknown"


class AnalyzeRequest(BaseModel):
    pipeline: str = Field(default="")
    build_number: str = Field(default="")
    branch: str = Field(default="")
    commit: str = Field(default="")
    stage: str = Field(default="unknown")
    status: str = Field(default="FAILED")
    errors: list[ErrorBlock] = Field(default_factory=list)


app = FastAPI(title="AI CI/CD Log Analyzer", version="0.1.0")


@app.on_event("startup")
def startup() -> None:
    init_db()


@app.post("/api/analyze")
def analyze_failure(request: AnalyzeRequest) -> dict[str, Any]:
    return analyze_and_store(request.model_dump())


@app.get("/api/latest-failure")
def latest_failure() -> dict[str, Any]:
    with connect() as conn:
        row = conn.execute("SELECT * FROM failures ORDER BY id DESC LIMIT 1").fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="No failures stored")
    return row_to_dict(row)


@app.get("/api/failures")
def failures() -> list[dict[str, Any]]:
    with connect() as conn:
        rows = conn.execute("SELECT * FROM failures ORDER BY id DESC LIMIT 50").fetchall()
    return [row_to_dict(row) for row in rows]


@app.get("/api/failures/{failure_id}")
def failure(failure_id: int) -> dict[str, Any]:
    with connect() as conn:
        row = conn.execute("SELECT * FROM failures WHERE id = ?", (failure_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Failure not found")
    return row_to_dict(row)
