/**
 * Injects an AI failure-analysis panel into Jenkins console/job pages.
 * Relies on utils.js (loaded first, see manifest.json) for escaping.
 */

const CONFIG = {
  apiBase: "http://localhost:8000",
  styleId: "trace-injected-style",
  panelId: "trace-injected-panel",
};

const PANEL_STYLE = `
  #${CONFIG.panelId} {
    margin: 16px 0;
    padding: 14px 16px;
    border: 1px solid #2c3644;
    border-left: 3px solid #ff5c5c;
    border-radius: 10px;
    background: #171d26;
    color: #e7ecf3;
    font-family: -apple-system, "Segoe UI", Inter, Arial, sans-serif;
  }
  #${CONFIG.panelId} .trace-panel-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 8px;
  }
  #${CONFIG.panelId} h2 {
    margin: 0;
    font-size: 15px;
    font-weight: 800;
    color: #e7ecf3;
  }
  #${CONFIG.panelId} p {
    margin: 6px 0;
    font-size: 13px;
    line-height: 1.5;
  }
  #${CONFIG.panelId} strong {
    color: #8b96a8;
    font-weight: 700;
  }
  #${CONFIG.panelId} .trace-dismiss {
    border: 1px solid #3a4557;
    border-radius: 6px;
    background: transparent;
    color: #8b96a8;
    font-size: 12px;
    padding: 3px 8px;
    cursor: pointer;
  }
  #${CONFIG.panelId} .trace-dismiss:hover {
    color: #e7ecf3;
    border-color: #8b96a8;
  }
`;

function injectStyleOnce() {
  if (document.getElementById(CONFIG.styleId)) return;
  const style = document.createElement("style");
  style.id = CONFIG.styleId;
  style.textContent = PANEL_STYLE;
  document.head.appendChild(style);
}

function isRelevantJenkinsPage() {
  return /console|consoleFull|\/job\//.test(window.location.href);
}

async function fetchLatestFailure() {
  const response = await fetch(`${CONFIG.apiBase}/api/latest-failure`);
  if (!response.ok) return null;
  return response.json();
}

function buildPanel(failure) {
  const { escapeHtml } = TraceUtils;
  const analysis = failure.ai_analysis || {};
  const panel = document.createElement("section");
  panel.id = CONFIG.panelId;
  panel.innerHTML = `
    <div class="trace-panel-head">
      <h2>AI Failure Analysis</h2>
      <button type="button" class="trace-dismiss">Dismiss</button>
    </div>
    <p><strong>Root cause:</strong> ${escapeHtml(analysis.root_cause)}</p>
    <p><strong>Explanation:</strong> ${escapeHtml(analysis.explanation)}</p>
    <p><strong>Suggested fix:</strong> ${escapeHtml((analysis.suggested_actions || [])[0] || "Check the backend analysis.")}</p>
  `;
  panel.querySelector(".trace-dismiss").addEventListener("click", () => panel.remove());
  return panel;
}

async function injectAnalysis() {
  if (!isRelevantJenkinsPage()) return;
  if (document.getElementById(CONFIG.panelId)) return; // already injected for this view

  try {
    const failure = await fetchLatestFailure();
    if (!failure?.ai_analysis) return;
    injectStyleOnce();
    document.body.prepend(buildPanel(failure));
  } catch (_) {
    // Do not disturb Jenkins pages if the backend is unavailable.
  }
}

// Jenkins (classic and Blue Ocean) navigates without full page reloads,
// so watch for URL changes rather than relying on a single load event.
function watchForNavigation() {
  let lastUrl = window.location.href;
  const observer = new MutationObserver(() => {
    if (window.location.href !== lastUrl) {
      lastUrl = window.location.href;
      injectAnalysis();
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });
}

injectAnalysis();
watchForNavigation();