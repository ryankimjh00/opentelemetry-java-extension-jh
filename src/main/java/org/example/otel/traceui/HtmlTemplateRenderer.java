// src/main/java/org/example/otel/traceui/HtmlTemplateRenderer.java
package org.example.otel.traceui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * trace.html 템플릿에 ViewModel을 바인딩하여 최종 HTML 생성
 * - 부모-자식 트리 가이드(세로선/엘보우) 포함
 * - 눈금/바 위치·너비는 data-*로 주고 JS에서 style에 적용(IDE CSS 경고 회피)
 */
final class HtmlTemplateRenderer {

    private HtmlTemplateRenderer() {
    }

    /** HTML 생성 진입점 */
    static String render(String traceId, List<SpanView> spans) {
        if (spans == null || spans.isEmpty()) {
            Map<String, Object> vm = new HashMap<String, Object>();
            vm.put("traceId", nz(traceId));
            vm.put("totalMs", "0.000");
            vm.put("spanCount", 0);
            vm.put("ticks", Collections.<Map<String, String>>emptyList());
            vm.put("rows", Collections.<Map<String, String>>emptyList());
            return TinyTemplate.render(loadResource("trace.html"), vm);
        }

        // parent → children 인덱스
        Map<String, List<SpanView>> children = indexByParent(spans);

        // 전체 구간 계산
        long minStart = Long.MAX_VALUE, maxEnd = Long.MIN_VALUE;
        for (int i = 0; i < spans.size(); i++) {
            SpanView s = spans.get(i);
            if (s.startNanos < minStart)
                minStart = s.startNanos;
            if (s.endNanos > maxEnd)
                maxEnd = s.endNanos;
        }
        final long totalNanos = Math.max(1L, maxEnd - minStart);
        final double totalMs = totalNanos / 1_000_000.0;

        // DFS로 Row 수집(조상 isLast 스택 유지)
        List<Row> rows = new ArrayList<Row>(spans.size());
        List<SpanView> roots = children.get("");
        if (roots != null) {
            for (int i = 0; i < roots.size(); i++) {
                Deque<Boolean> stack = new ArrayDeque<Boolean>();
                buildRowsDFS(roots.get(i), children, 0, i == roots.size() - 1, stack, rows);
            }
        }

        // ViewModel 구성
        Map<String, Object> vm = new HashMap<String, Object>();
        vm.put("traceId", nz(traceId));
        vm.put("totalMs", String.format(Locale.ROOT, "%.3f", totalMs));
        vm.put("spanCount", spans.size());
        vm.put("ticks", buildTicks(totalMs, 5));
        vm.put("rows", buildRowModels(rows, minStart, totalNanos));

        int maxDepth = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).depth > maxDepth)
                maxDepth = rows.get(i).depth;
        }
        vm.put("maxDepth", maxDepth);

        return TinyTemplate.render(loadResource("trace.html"), vm);
    }

    /* ---------------- ViewModel 빌더 ---------------- */

    private static List<Map<String, String>> buildTicks(double totalMs, int tickCount) {
        double stepMs = Math.max(0.1, Math.round((totalMs / tickCount) * 10.0) / 10.0);
        List<Map<String, String>> tks = new ArrayList<Map<String, String>>(tickCount + 1);
        for (int i = 0; i <= tickCount; i++) {
            double r = (double) i / (double) tickCount;
            double ms = (i == tickCount) ? totalMs : stepMs * i;
            Map<String, String> k = new HashMap<String, String>();
            k.put("leftPct", pct(r));
            k.put("label", (i == 0) ? "0" : trimMs(ms));
            tks.add(k);
        }
        return tks;
    }

    private static List<Map<String, Object>> buildRowModels(List<Row> rows, long minStart, long totalNanos) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            SpanView s = r.span;

            long spanDur = Math.max(1L, s.endNanos - s.startNanos);
            double off = clamp01((double) (s.startNanos - minStart) / (double) totalNanos);
            double len = clamp01((double) spanDur / (double) totalNanos);

            String widthVal = (len * 100.0 < 0.5) ? "3px"
                    : String.format(Locale.ROOT, "%.1f%%", len * 100.0);

            String barClass = "";
            if (s.httpStatusCode != null) {
                Integer sc = toHttpStatus(s.httpStatusCode);
                if (sc != null) {
                    if (sc >= 500)
                        barClass = " err";
                    else if (sc >= 400)
                        barClass = " warn";
                }
            }

            Map<String, Object> m = new HashMap<String, Object>();
            m.put("spanId", nz(s.spanId));
            m.put("name", nz(s.name));
            m.put("durationMs", msStr(spanDur));
            m.put("leftPct", pct(off));
            m.put("widthVal", widthVal);
            m.put("barClass", barClass);
            m.put("treeText", buildTreeText(r));
            m.put("depth", Integer.toString(r.depth)); // ← data-depth로 내려보낼 값

            // ★ 이벤트 목록 추가
            m.put("events", buildEventModels(s, minStart, totalNanos));

            list.add(m);
        }
        return list;
    }

    private static List<Map<String, String>> buildEventModels(SpanView s, long minStart, long totalNanos) {
        if (s.events == null || s.events.isEmpty())
            return java.util.Collections.emptyList();

        List<Map<String, String>> evs = new ArrayList<>(s.events.size());
        for (Object evObj : s.events) {
            // 1) 가능한 절대 ns 필드/메서드 후보들
            long abs = firstNonZero(
                    getLongByNames(evObj, "timeNanos", "timeEpochNanos", "timeUnixNanos", "epochNanos", "nanos"),
                    0L);

            // 2) 절대 ns가 없으면(=0) 상대 ns 후보를 span 시작에 더해 절대화
            if (abs == 0L) {
                long rel = firstNonZero(
                        getLongByNames(evObj, "offsetNanos", "relativeNanos", "deltaNanos", "timeOffsetNanos"),
                        0L);
                if (rel > 0L)
                    abs = s.startNanos + rel;
            }

            // 3) 그래도 못 구하면(=이벤트 타임 미제공) 스킵
            if (abs <= 0L)
                continue;

            // 4) 트레이스 전체(minStart~minStart+totalNanos) 대비 좌표(0.0~1.0) 계산
            double offRatio = clamp01((double) (abs - minStart) / (double) totalNanos);

            Map<String, String> m = new HashMap<>();
            m.put("leftPct", pct(offRatio)); // "23.4%" 형태
            m.put("label", nz(getStringByNames(evObj, // 이벤트 이름(툴팁)
                    "name", "eventName", "type", "kind", "key")));
            evs.add(m);
        }
        return evs;
    }

    private static String buildTreeText(Row r) {
        if (r.depth <= 0)
            return ""; // 루트는 접두사 없음
        StringBuilder sb = new StringBuilder(r.depth * 3 + 2);
        for (int i = 0; i < r.depth - 1; i++) {
            boolean ancIsLast = r.ancLast[i];
            sb.append(ancIsLast ? "   " : "│  ");
        }
        sb.append(r.isLast ? "└─ " : "├─ ");
        return sb.toString();
    }

    /* ---------------- DFS + 인덱스 ---------------- */

    private static void buildRowsDFS(SpanView s, Map<String, List<SpanView>> children,
            int depth, boolean isLast, Deque<Boolean> ancStack, List<Row> out) {
        boolean[] ancLast = new boolean[ancStack.size()];
        int idx = 0;
        for (Boolean b : ancStack)
            ancLast[idx++] = (b != null && b.booleanValue());
        out.add(new Row(s, depth, isLast, ancLast));

        List<SpanView> ch = children.get(s.spanId);
        if (ch != null && !ch.isEmpty()) {
            ancStack.addLast(Boolean.valueOf(isLast));
            for (int i = 0; i < ch.size(); i++) {
                buildRowsDFS(ch.get(i), children, depth + 1, i == ch.size() - 1, ancStack, out);
            }
            ancStack.removeLast();
        }
    }

    private static Map<String, List<SpanView>> indexByParent(List<SpanView> spans) {
        HashSet<String> ids = new HashSet<String>(spans.size() * 2);
        for (int i = 0; i < spans.size(); i++)
            ids.add(spans.get(i).spanId);
        Map<String, List<SpanView>> children = new HashMap<String, List<SpanView>>();
        for (int i = 0; i < spans.size(); i++) {
            SpanView s = spans.get(i);
            String parent = (s.parentSpanId != null && ids.contains(s.parentSpanId)) ? s.parentSpanId : "";
            List<SpanView> lst = children.get(parent);
            if (lst == null) {
                lst = new ArrayList<SpanView>();
                children.put(parent, lst);
            }
            lst.add(s);
        }
        return children;
    }

    /* ---------------- 포맷/보조 ---------------- */

    private static String loadResource(String name) {
        try (InputStream in = HtmlTemplateRenderer.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null)
                throw new IllegalStateException("template not found: " + name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0)
                bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String msStr(long nanos) {
        double ms = nanos / 1_000_000.0;
        return String.format(Locale.ROOT, "%.3fms", ms);
    }

    private static String trimMs(double ms) {
        if (ms >= 100.0)
            return String.valueOf((int) Math.round(ms));
        return String.format(Locale.ROOT, "%.1f", ms);
    }

    private static Integer toHttpStatus(Object v) {
        try {
            if (v instanceof Number)
                return ((Number) v).intValue();
            if (v instanceof String)
                return Integer.parseInt((String) v);
        } catch (Exception ignore) {
        }
        return null;
    }

    /** 내부 Row 모델 */
    private static final class Row {
        final SpanView span;
        final int depth;
        final boolean isLast;
        final boolean[] ancLast;

        Row(SpanView s, int d, boolean last, boolean[] a) {
            this.span = s;
            this.depth = d;
            this.isLast = last;
            this.ancLast = a;
        }
    }

    /**
     * 주어진 객체에서 "필드명/게터명 후보들"을 순서대로 시도하여 long 값을 얻는다.
     * - 우선순위: public 필드 → getXxx()/xxx() 메서드 → 실패 시 0 반환
     * - 예: "timeNanos" → 필드 시도 → getTimeNanos() → timeNanos()
     */
    private static long getLongByNames(Object obj, String... names) {
        for (String n : names) {
            // 1) public 필드 직접 접근
            try {
                java.lang.reflect.Field f = obj.getClass().getField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number)
                    return ((Number) v).longValue();
            } catch (NoSuchFieldException ignore) {
            } catch (Exception e) {
                // 다른 리플렉션 예외는 무시(안전 우선)
            }

            // 2) getXxx() 접근
            try {
                String getter = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = obj.getClass().getMethod(getter);
                Object v = m.invoke(obj);
                if (v instanceof Number)
                    return ((Number) v).longValue();
            } catch (NoSuchMethodException ignore) {
            } catch (Exception e) {
            }

            // 3) xxx() 접근
            try {
                java.lang.reflect.Method m = obj.getClass().getMethod(n);
                Object v = m.invoke(obj);
                if (v instanceof Number)
                    return ((Number) v).longValue();
            } catch (NoSuchMethodException ignore) {
            } catch (Exception e) {
            }
        }
        return 0L;
    }

    /**
     * 문자열 후보들을 순서대로 시도하여 처음으로 유효한(비어있지 않은) 문자열을 반환.
     * - public 필드, getXxx(), xxx() 순으로 시도
     */
    private static String getStringByNames(Object obj, String... names) {
        for (String n : names) {
            // 1) public 필드
            try {
                java.lang.reflect.Field f = obj.getClass().getField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty())
                        return s;
                }
            } catch (NoSuchFieldException ignore) {
            } catch (Exception e) {
            }

            // 2) getXxx()
            try {
                String getter = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = obj.getClass().getMethod(getter);
                Object v = m.invoke(obj);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty())
                        return s;
                }
            } catch (NoSuchMethodException ignore) {
            } catch (Exception e) {
            }

            // 3) xxx()
            try {
                java.lang.reflect.Method m = obj.getClass().getMethod(n);
                Object v = m.invoke(obj);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty())
                        return s;
                }
            } catch (NoSuchMethodException ignore) {
            } catch (Exception e) {
            }
        }
        return "";
    }

    /** 여러 long 값 중 첫 번째로 0이 아닌 값을 반환(없으면 defaultVal) */
    private static long firstNonZero(long val, long defaultVal) {
        return val != 0L ? val : defaultVal;
    }

    /** 0.0~1.0 사이로 고정(바운딩) */
    private static double clamp01(double v) {
        if (v < 0.0)
            return 0.0;
        if (v > 1.0)
            return 1.0;
        return v;
    }

    /** 0.0~1.0 값을 "xx.x%" 문자열로 포맷 */
    private static String pct(double ratio) {
        return String.format(java.util.Locale.ROOT, "%.3f%%", ratio * 100.0);
    }

    /** null → "" 치환 */
    private static String nz(String s) {
        return (s == null) ? "" : s;
    }
}
