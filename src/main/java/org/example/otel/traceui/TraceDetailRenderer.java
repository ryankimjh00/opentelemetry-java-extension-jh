// src/main/java/org/example/otel/traceui/TraceDetailRenderer.java
package org.example.otel.traceui;

import java.util.*;

/**
 * Trace 상세 렌더러
 * - ASCII: 기존 출력 유지
 * - HTML : 템플릿 기반(HtmlTemplateRenderer → trace.html)
 */
final class TraceDetailRenderer {

    /* ========== ASCII 출력(기존 유지) ========== */
    static String renderAscii(String traceId, List<SpanView> spans) {
        StringBuilder out = new StringBuilder(256 + spans.size() * 64);
        out.append("# trace ").append(traceId).append("\n");

        Map<String, List<SpanView>> children = indexByParent(spans);
        Deque<Boolean> lastStack = new ArrayDeque<Boolean>();
        List<SpanView> roots = children.get("");
        if (roots != null) {
            for (int i = 0; i < roots.size(); i++) {
                walkAscii(roots.get(i), children, lastStack, i == roots.size() - 1, out);
            }
        }
        return out.toString();
    }

    /* ========== HTML 출력(템플릿 바인딩) ========== */
    static String renderHtml(String traceId, List<SpanView> spans) {
        return HtmlTemplateRenderer.render(traceId, spans);
    }

    /* ---- ASCII 유틸 (원본 유지) ---- */
    private static Map<String, List<SpanView>> indexByParent(List<SpanView> spans) {
        HashSet<String> ids = new HashSet<String>(spans.size() * 2);
        for (int i = 0; i < spans.size(); i++) ids.add(spans.get(i).spanId);
        Map<String, List<SpanView>> children = new HashMap<String, List<SpanView>>();
        for (int i = 0; i < spans.size(); i++) {
            SpanView s = spans.get(i);
            String parent = (s.parentSpanId != null && ids.contains(s.parentSpanId)) ? s.parentSpanId : "";
            List<SpanView> lst = children.get(parent);
            if (lst == null) { lst = new ArrayList<SpanView>(); children.put(parent, lst); }
            lst.add(s);
        }
        return children;
    }

    private static void walkAscii(SpanView s, Map<String, List<SpanView>> children,
                                  Deque<Boolean> lastStack, boolean isLast, StringBuilder out) {
        StringBuilder prefix = new StringBuilder();
        for (Boolean last : lastStack) prefix.append(last.booleanValue() ? "   " : "│  ");
        prefix.append(isLast ? "└─ " : "├─ ");
        String extra = "";
        if (s.httpMethod != null || s.httpStatusCode != null) {
            extra = " [" + (s.httpMethod != null ? s.httpMethod : "")
                    + ((s.httpMethod != null && s.httpStatusCode != null) ? " " : "")
                    + (s.httpStatusCode != null ? ("status=" + s.httpStatusCode) : "") + "]";
        }
        out.append(prefix).append(s.name)
           .append(" (").append(msStr(s.endNanos - s.startNanos)).append(")")
           .append(extra).append("\n");
        // out.append(prefix).append(s.service.isEmpty() ? "?" : s.service)
        //    .append(" · ").append(s.name)
        //    .append(" (").append(msStr(s.endNanos - s.startNanos)).append(")")
        //    .append(extra).append("\n");

        List<SpanView> ch = children.get(s.spanId);
        if (ch != null && !ch.isEmpty()) {
            for (int i = 0; i < ch.size(); i++) {
                lastStack.addLast(Boolean.valueOf(isLast));
                walkAscii(ch.get(i), children, lastStack, i == ch.size() - 1, out);
                lastStack.removeLast();
            }
        }
    }

    private static String msStr(long nanos) {
        double ms = nanos / 1_000_000.0;
        return String.format(java.util.Locale.ROOT, "%.3fms", ms);
    }
}
