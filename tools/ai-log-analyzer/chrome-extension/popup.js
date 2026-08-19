const API_BASE = "http://localhost:8000";
let failures = [];
let selectedId = null;

async function loadFailures() {
  const content = document.getElementById("content");
  try {
    const response = await fetch(`${API_BASE}/api/failures`);
    if (!response.ok) {
      throw new Error(`Backend returned ${response.status}`);
    }
    failures = await response.json();
    selectedId = failures[0]?.id ?? null;
    renderHistory();
    renderSelectedFailure();
  } catch (error) {
    failures = [];
    renderHistory();
    content.innerHTML = `<div class="empty">No analysis available: ${escapeHtml(error.message)}</div>`;
  }
}

function renderHistory() {
  const history = document.getElementById("history");
  const count = document.getElementById("count");
  count.textContent = `${failures.length} ${failures.length === 1 ? "failure" : "failures"}`;

  if (!failures.length) {
    history.innerHTML = `<div class="empty">No failed pipeline history.</div>`;
    return;
  }

  history.innerHTML = failures
    .map((failure) => {
      const analysis = failure.ai_analysis || {};
      const isActive = failure.id === selectedId ? " active" : "";
      const known = analysis.known_error
        ? `<span class="history-badge">${escapeHtml(analysis.occurrence_count)} times</span>`
        : `<span class="history-badge new">new</span>`;
      return `
        <button class="history-item${isActive}" type="button" data-id="${escapeHtml(failure.id)}">
          <div class="history-main">${escapeHtml(displayText(failure.pipeline || "unknown-pipeline"))}</div>
          <div class="history-meta">Build #${escapeHtml(failure.build_number || "n/a")} | ${escapeHtml(displayText(failure.branch || "n/a"))}</div>
          <div class="history-meta">${escapeHtml(analysis.stage || failure.stage || "unknown")}</div>
          <div class="history-row">
            <span class="category-badge">${escapeHtml(analysis.category || "Generic")}</span>
            ${known}
          </div>
        </button>
      `;
    })
    .join("");

  history.querySelectorAll(".history-item").forEach((item) => {
    item.addEventListener("click", () => {
      selectedId = Number(item.dataset.id);
      renderHistory();
      renderSelectedFailure();
    });
  });
}

function renderSelectedFailure() {
  const content = document.getElementById("content");
  if (!failures.length) {
    content.innerHTML = `<div class="empty">No failure history stored yet.</div>`;
    return;
  }

  const failure = failures.find((item) => item.id === selectedId) || failures[0];
  const analysis = failure.ai_analysis || {};
  const actions = analysis.suggested_actions || [];
  const confidence = Math.round((analysis.confidence || 0) * 100);
  content.innerHTML = `
    <div class="card">
      <div class="detail-header">
        <div class="detail-title">
          <h2>${escapeHtml(analysis.root_cause || "Unknown failure")}</h2>
          <div class="detail-subtitle">Failure ID ${escapeHtml(failure.id)} | Build #${escapeHtml(failure.build_number)}</div>
        </div>
        <span class="status-badge">${escapeHtml(failure.status || "FAILED")}</span>
      </div>
      <div class="meta-grid">
        ${renderMeta("Pipeline", displayText(failure.pipeline))}
        ${renderMeta("Branch", displayText(failure.branch || "n/a"))}
        ${renderMeta("Stage", analysis.stage || failure.stage || "unknown")}
        ${renderMeta("Category", analysis.category || "Generic")}
      </div>
      <div class="detail-body">
        <div class="summary">${escapeHtml(analysis.summary || "No summary available.")}</div>
        ${renderKnownError(analysis)}
        ${renderSection("Explanation", analysis.explanation || "No explanation available.")}
        <div class="section">
          <div class="section-label">Suggested Fix</div>
          <ol class="actions-list">${actions.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ol>
        </div>
        <div class="section confidence">
          <div>
            <div class="section-label">Confidence</div>
            <div class="confidence-track"><div class="confidence-fill" style="width: ${escapeHtml(confidence)}%"></div></div>
          </div>
          <strong>${escapeHtml(confidence)}%</strong>
        </div>
      </div>
    </div>
  `;
}

function renderMeta(label, value) {
  return `
    <div class="meta-item">
      <div class="meta-label">${escapeHtml(label)}</div>
      <div class="meta-value">${escapeHtml(value || "n/a")}</div>
    </div>
  `;
}

function renderSection(label, value) {
  return `
    <div class="section">
      <div class="section-label">${escapeHtml(label)}</div>
      <div class="section-content">${escapeHtml(value)}</div>
    </div>
  `;
}

function renderKnownError(analysis) {
  if (!analysis.known_error) {
    return `<div class="known new">New error pattern</div>`;
  }
  return `
    <div class="known">
      This error has occurred ${escapeHtml(analysis.occurrence_count)} times.
      <div class="section-label">Previous Solution</div>
      <div class="section-content">${escapeHtml(analysis.previous_solution || (analysis.suggested_actions || []).join("\\n"))}</div>
    </div>
  `;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function displayText(value) {
  try {
    return decodeURIComponent(String(value ?? ""));
  } catch {
    return String(value ?? "");
  }
}

loadFailures();
