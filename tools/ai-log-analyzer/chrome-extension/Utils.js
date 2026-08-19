/**
 * Shared helpers for the Trace extension.
 * Loaded by both popup.html and content.js so escaping/formatting
 * logic lives in exactly one place.
 */

const TraceUtils = (() => {
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

  /** "new" | "known" — drives badge color, rail color, and trace-node color. */
  function severityOf(analysis) {
    return analysis?.known_error ? "known" : "new";
  }

  function confidencePercent(analysis) {
    const raw = Number(analysis?.confidence ?? 0);
    return Math.max(0, Math.min(100, Math.round(raw * 100)));
  }

  return { escapeHtml, displayText, severityOf, confidencePercent };
})();

// Node/CommonJS export for future unit tests, without affecting the browser global above.
if (typeof module !== "undefined" && module.exports) {
  module.exports = TraceUtils;
}