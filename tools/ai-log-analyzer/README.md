# AI CI/CD Log Analyzer

This module analyzes Jenkins failures without sending the full console log to an AI service.

Flow:

```text
Jenkins failure
  -> save console log
  -> extract useful error blocks
  -> redact secrets
  -> POST extracted JSON to backend
  -> backend stores analysis in SQLite
  -> Chrome extension displays latest analysis
```

## Run Extractor Locally

```powershell
python tools\ai-log-analyzer\extract_errors.py `
  --input path\to\jenkins.log `
  --output target\ai-failure\extracted-errors.json `
  --pipeline customer-api `
  --build-number 125
```

## Run Backend Locally

Create a private `.env` file:

```text
tools/ai-log-analyzer/backend/.env
```

Example:

```text
AI_ANALYZER_DATABASE_URL=sqlite:///./data/failures.db
AI_ANALYZER_AI_PROVIDER=heuristic
AI_ANALYZER_AI_API_KEY=
AI_ANALYZER_AI_MODEL=gpt-4.1-mini
```

```powershell
cd tools\ai-log-analyzer\backend
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt
.\.venv\Scripts\uvicorn app.main:app --reload --port 8000
```

API:

```text
POST /api/analyze
GET  /api/latest-failure
GET  /api/failures
GET  /api/failures/{id}
```

## Docker

```powershell
docker compose up -d --build ai-log-analyzer
```

## Chrome Extension

Open Chrome:

```text
chrome://extensions
```

Enable Developer Mode, then load:

```text
tools/ai-log-analyzer/chrome-extension
```

The popup reads:

```text
http://localhost:8000/api/failures
```

It shows a clickable failure history list. Each row includes the pipeline name, build number, branch, category, stage, and known-error count. Selecting a row opens that failure analysis.

## Previous Error Knowledge

The backend stores a normalized fingerprint for each new error pattern. The fingerprint removes volatile values such as timestamps, build numbers, commit hashes, versions, paths, and raw numbers before hashing.

On repeated failures:

```text
same normalized error -> known_error=true -> occurrence_count increases -> previous solution is reused
```

This avoids calling a real AI provider for failures that already have a stored solution.

## Security

- Do not put the AI API key in Jenkins or the Chrome extension.
- Keep the AI key only in the backend environment.
- Logs are redacted before they are sent to the backend.
- The first backend uses a deterministic local analyzer so the project works without an AI key.
