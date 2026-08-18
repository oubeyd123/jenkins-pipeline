const ANALYZER_API_BASE = "http://localhost:8000";

async function injectAnalysis() {
  if (!/console|consoleFull|job/.test(window.location.href)) {
    return;
  }

  try {
    const response = await fetch(`${ANALYZER_API_BASE}/api/latest-failure`);
    if (!response.ok) {
      return;
    }
    const failure = await response.json();
    const analysis = failure.ai_analysis;
    const panel = document.createElement("section");
    panel.style.cssText = "margin:16px 0;padding:16px;border:1px solid #8ab4f8;border-radius:8px;background:#eef5ff;color:#172033";
    panel.innerHTML = `
      <h2 style="margin:0 0 8px;font-size:18px">AI Failure Analysis</h2>
      <p><strong>Root Cause:</strong> ${escapeHtml(analysis.root_cause)}</p>
      <p><strong>Explanation:</strong> ${escapeHtml(analysis.explanation)}</p>
      <p><strong>Suggested Fix:</strong> ${escapeHtml((analysis.suggested_actions || [])[0] || "Check the backend analysis.")}</p>
    `;
    document.body.prepend(panel);
  } catch (_) {
    // Do not disturb Jenkins pages if the backend is unavailable.
  }
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

injectAnalysis();
