const { escapeHtml, displayText, severityOf, confidencePercent } = TraceUtils;

const CONFIG = {
  apiBase: "http://localhost:8000",
};

/** Single source of truth for popup state. Every render() reads from here. */
const state = {
  failures: [],
  selectedId: null,
  selectedErrorIndex: 0,
  filter: "all", // "all" | "new" | "known"
  query: "",
  loadError: null,
};

const els = {};

function cacheElements() {
  els.history = document.getElementById("history");
  els.content = document.getElementById("content");
  els.count = document.getElementById("count");
  els.search = document.getElementById("search");
  els.filters = document.getElementById("filters");
  els.panelToggle = document.getElementById("panelToggle");
  els.panelOpenRail = document.getElementById("panelOpenRail");
}

/* ---------------------------------------------------------------- */
/* Preferences (panel visibility)                                    */
/* ---------------------------------------------------------------- */

function initPreferences() {
  const savedPanel = localStorage.getItem("tracePanel") || "open";
  setPanel(savedPanel);

  els.panelToggle.addEventListener("click", () => {
    const isCollapsed = document.body.classList.contains("panel-collapsed");
    const next = isCollapsed ? "open" : "closed";
    setPanel(next);
    localStorage.setItem("tracePanel", next);
  });

  els.panelOpenRail.addEventListener("click", () => {
    setPanel("open");
    localStorage.setItem("tracePanel", "open");
  });
}

function setPanel(panelState) {
  const isClosed = panelState === "closed";
  document.body.classList.toggle("panel-collapsed", isClosed);
  els.panelToggle.textContent = "<";
  els.panelToggle.title = "Hide failure history";
  els.panelToggle.setAttribute("aria-label", els.panelToggle.title);
  els.panelOpenRail.title = "Show failure history";
  els.panelOpenRail.setAttribute("aria-label", els.panelOpenRail.title);
}

/* ---------------------------------------------------------------- */
/* Data loading                                                      */
/* ---------------------------------------------------------------- */

async function loadFailures() {
  els.content.innerHTML = `<div class="empty">Loading latest failure…</div>`;
  els.history.innerHTML = `<div class="empty">Loading…</div>`;

  try {
    const response = await fetch(`${CONFIG.apiBase}/api/failures`);
    if (!response.ok) {
      throw new Error(`Backend returned ${response.status}`);
    }
    state.failures = await response.json();
    state.loadError = null;
    state.selectedId = state.failures[0]?.id ?? null;
    state.selectedErrorIndex = 0;
  } catch (error) {
    state.failures = [];
    state.loadError = error.message;
  }

  render();
}

/* ---------------------------------------------------------------- */
/* Derived data                                                      */
/* ---------------------------------------------------------------- */

function visibleFailures() {
  const query = state.query.trim().toLowerCase();

  return state.failures.filter((failure) => {
    const analysis = failure.ai_analysis || {};
    if (state.filter !== "all" && severityOf(analysis) !== state.filter) {
      return false;
    }
    if (!query) return true;

    const haystack = [
      displayText(failure.pipeline),
      displayText(failure.branch),
      analysis.stage || failure.stage,
      analysis.category,
    ]
      .join(" ")
      .toLowerCase();

    return haystack.includes(query);
  });
}

/* ---------------------------------------------------------------- */
/* Rendering                                                          */
/* ---------------------------------------------------------------- */

function render() {
  renderCount();
  renderHistory();
  renderSelectedFailure();
}

function renderCount() {
  const total = state.failures.length;
  els.count.textContent = `${total} ${total === 1 ? "failure" : "failures"}`;
}

function renderHistory() {
  if (state.loadError) {
    els.history.innerHTML = `<div class="empty">Couldn't load history: ${escapeHtml(state.loadError)}</div>`;
    return;
  }

  const visible = visibleFailures();

  if (!state.failures.length) {
    els.history.innerHTML = `<div class="empty">No failed pipeline history.</div>`;
    return;
  }

  if (!visible.length) {
    els.history.innerHTML = `<div class="empty">No failures match this filter.</div>`;
    return;
  }

  els.history.innerHTML = visible.map(historyItemHtml).join("");
}

function historyItemHtml(failure) {
  const analysis = failure.ai_analysis || {};
  const severity = severityOf(analysis);
  const isActive = failure.id === state.selectedId;
  const known =
    severity === "known"
      ? `<span class="history-badge">${escapeHtml(analysis.occurrence_count)}x seen</span>`
      : `<span class="history-badge new">new</span>`;

  return `
    <button class="history-item${isActive ? " active" : ""}" type="button" data-id="${escapeHtml(failure.id)}">
      <span class="status-ball" title="${escapeHtml(failure.status || "FAILED")}"></span>
      <span>
        <div class="history-main">${escapeHtml(displayText(failure.pipeline || "unknown-pipeline"))}</div>
        <div class="history-meta">Build #${escapeHtml(failure.build_number || "n/a")} | ${escapeHtml(displayText(failure.branch || "n/a"))}</div>
        <div class="history-meta">${escapeHtml(analysis.stage || failure.stage || "unknown")}</div>
        <div class="history-row">
          <span class="category-badge">${escapeHtml(analysis.category || "Generic")}</span>
          ${known}
        </div>
      </span>
    </button>
  `;
}

function renderSelectedFailure() {
  if (state.loadError) {
    els.content.innerHTML = `<div class="empty">No analysis available: ${escapeHtml(state.loadError)}</div>`;
    return;
  }

  if (!state.failures.length) {
    els.content.innerHTML = `<div class="empty">No failure history stored yet.</div>`;
    return;
  }

  const failure = state.failures.find((item) => item.id === state.selectedId) || state.failures[0];
  const primaryAnalysis = failure.ai_analysis || {};
  const allAnalyses = primaryAnalysis.error_analyses?.length ? primaryAnalysis.error_analyses : [primaryAnalysis];
  const analysis = allAnalyses[state.selectedErrorIndex] || allAnalyses[0] || primaryAnalysis;
  const actions = analysis.suggested_actions || [];
  const confidence = confidencePercent(analysis);

  els.content.innerHTML = `
    <div class="card">
      <div class="detail-header">
        <div class="detail-title">
          <h2>${escapeHtml(analysis.root_cause || "Unknown failure")}</h2>
          <div class="detail-subtitle">Failure ID ${escapeHtml(failure.id)} | Build #${escapeHtml(failure.build_number)}</div>
        </div>
        <span class="status-badge">${escapeHtml(failure.status || "FAILED")}</span>
      </div>
      <div class="meta-grid">
        ${metaItemHtml("Pipeline", displayText(failure.pipeline))}
        ${metaItemHtml("Branch", displayText(failure.branch || "n/a"))}
        ${metaItemHtml("Stage", analysis.stage || failure.stage || "unknown")}
        ${metaItemHtml("Category", analysis.category || "Generic")}
      </div>
      <div class="detail-body">
        ${errorSelectorHtml(allAnalyses)}
        <div class="summary">${escapeHtml(analysis.summary || "No summary available.")}</div>
        ${knownErrorHtml(analysis)}
        ${sectionHtml("Explanation", analysis.explanation || "No explanation available.")}
        <div class="section">
          <div class="section-label">Suggested Fix</div>
          <ol class="actions-list">${actions.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ol>
        </div>
        <div class="section confidence">
          <div>
            <div class="section-label">Confidence</div>
            <div class="confidence-track"><div class="confidence-fill" style="width: ${confidence}%"></div></div>
          </div>
          <strong>${confidence}%</strong>
        </div>
      </div>
    </div>
  `;
}

function errorSelectorHtml(analyses) {
  if (!analyses || analyses.length <= 1) {
    return "";
  }

  return `
    <div class="error-switcher">
      <div class="section-label">Errors in this build</div>
      <div class="error-list">
        ${analyses.map(errorChoiceHtml).join("")}
      </div>
    </div>
  `;
}

function errorChoiceHtml(analysis) {
  const index = Number(analysis.error_index ?? 0);
  const isActive = index === state.selectedErrorIndex;
  const label = analysis.stage || `Error ${index + 1}`;
  const line = analysis.category || "Generic";
  return `
    <button class="error-choice${isActive ? " active" : ""}" type="button" data-error-index="${escapeHtml(index)}">
      <span class="error-choice-title">${escapeHtml(label)}</span>
      <span class="error-choice-meta">${escapeHtml(line)}</span>
    </button>
  `;
}

function metaItemHtml(label, value) {
  return `
    <div class="meta-item">
      <div class="meta-label">${escapeHtml(label)}</div>
      <div class="meta-value">${escapeHtml(value || "n/a")}</div>
    </div>
  `;
}

function sectionHtml(label, value) {
  return `
    <div class="section">
      <div class="section-label">${escapeHtml(label)}</div>
      <div class="section-content">${escapeHtml(value)}</div>
    </div>
  `;
}

function knownErrorHtml(analysis) {
  if (severityOf(analysis) !== "known") {
    return `<div class="known new">New error pattern</div>`;
  }
  return `
    <div class="known">
      This error has occurred ${escapeHtml(analysis.occurrence_count)} times.
      <div class="section-label" style="margin-top:6px">Previous Solution</div>
      <div class="section-content">${escapeHtml(analysis.previous_solution || (analysis.suggested_actions || []).join("\n"))}</div>
    </div>
  `;
}

/* ---------------------------------------------------------------- */
/* Events (delegated — bound once, not re-bound on every render)     */
/* ---------------------------------------------------------------- */

function bindEvents() {
  els.history.addEventListener("click", (event) => {
    const item = event.target.closest(".history-item");
    if (!item) return;
    state.selectedId = Number(item.dataset.id);
    state.selectedErrorIndex = 0;
    render();
  });

  els.content.addEventListener("click", (event) => {
    const item = event.target.closest(".error-choice");
    if (!item) return;
    state.selectedErrorIndex = Number(item.dataset.errorIndex || 0);
    renderSelectedFailure();
  });

  els.filters.addEventListener("click", (event) => {
    const chip = event.target.closest(".filter-chip");
    if (!chip) return;
    state.filter = chip.dataset.filter;
    [...els.filters.querySelectorAll(".filter-chip")].forEach((c) => c.classList.toggle("active", c === chip));
    renderCount();
    renderHistory();
  });

  let searchDebounce;
  els.search.addEventListener("input", (event) => {
    clearTimeout(searchDebounce);
    const value = event.target.value;
    searchDebounce = setTimeout(() => {
      state.query = value;
      renderHistory();
    }, 120);
  });
}

/* ---------------------------------------------------------------- */
/* Init                                                               */
/* ---------------------------------------------------------------- */

function init() {
  cacheElements();
  initPreferences();
  bindEvents();
  loadFailures();
}

init();
