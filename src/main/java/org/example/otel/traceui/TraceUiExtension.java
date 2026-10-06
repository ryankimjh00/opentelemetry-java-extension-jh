package org.example.otel.traceui;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.io.HttpRequestHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.http.protocol.HttpContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OTel Java Agent 확장: InMemorySpanProcessor 등록 + 초경량 내장 HTTP 뷰어 기동.
 *
 * 제공 엔드포인트:
 * - "/" : 최근 trace 목록
 * - "/trace/{id}" : 상세(ASCII/HTML: ?format=ascii|html)
 * - "/healthz" : 헬스 체크
 * - "/api/span" : 스팬 상세 JSON (우측/하단 패널에서 사용)
 * - "/api/trace/{id}/service-graph" : 서비스/스팬 그래프(JSON)
 *
 * 시스템 속성:
 * - otel.javaagent.extension.traceui.bind (기본 0.0.0.0)
 * - otel.javaagent.extension.traceui.port (기본 55679)
 * - otel.javaagent.extension.traceui.maxTraces (기본 64)
 * - otel.javaagent.extension.traceui.maxSpansPerTrace (기본 2048)
 * - otel.javaagent.extension.traceui.ttlSeconds (기본 600)
 */
public final class TraceUiExtension implements AutoConfigurationCustomizerProvider {

    // ────────────────────────────── 경로/키 상수 ──────────────────────────────
    private static final String PATH_ROOT = "/";
    private static final String PATH_TRACE_PREFIX = "/trace/";
    private static final String PATH_HEALTHZ = "/healthz";
    private static final String PATH_FAVICON = "/favicon.ico";
    private static final String PATH_JS_PREFIX = "/js/*";
    private static final String PATH_CSS_PREFIX = "/css/*";
    private static final String PATH_API_SPAN = "/api/span";
    private static final String PATH_API_TRACE_PREFIX = "/api/trace/*";
    private static final String SERVICE_GRAPH_SUFFIX = "/service-graph";

    private static final String Q_LIMIT = "limit";
    private static final String Q_FORMAT = "format";
    private static final String FORMAT_ASCII = "ascii";
    private static final String FORMAT_HTML = "html";
    private static final String Q_TRACE_ID = "traceId";
    private static final String Q_SPAN_ID = "spanId";
    private static final String Q_MODE = "mode";
    private static final String MODE_SPAN = "span";
    private static final String Q_MAX_DEPTH = "maxDepth";

    private static final AtomicBoolean SERVER_STARTED = new AtomicBoolean(false);
    private static volatile TraceStore STORE; // ← 하나만 공유

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addTracerProviderCustomizer((SdkTracerProviderBuilder builder, ConfigProperties config) -> {
            // 1) 우선 설정 반영
            Conf.init(config);

            // 2) 저장소 준비 (설정값 반영 후 생성)
            final TraceStore store = getOrCreateStore();

            // 3) SpanProcessor 등록
            builder.addSpanProcessor(new InMemorySpanProcessor(store));

            // 4) 서버는 단 한 번만 시작 (설정 반영된 상태에서)
            if (SERVER_STARTED.compareAndSet(false, true)) {
                startHttpServer(store);
            }
            return builder;
        });
    }

    private static TraceStore getOrCreateStore() {
        final TraceStore s = STORE;
        if (s != null)
            return s;
        synchronized (TraceUiExtension.class) {
            if (STORE == null) {
                STORE = new TraceStore(
                        Conf.maxTraces(), Conf.maxSpansPerTrace(), Duration.ofSeconds(Conf.ttlSeconds()));
            }
            return STORE;
        }
    }

    /** 내장 HTTP 서버 시작 및 모든 핸들러 등록 */
    private static void startHttpServer(final TraceStore store) {
        final String bind = Conf.bindAddress();
        final int port = Conf.port();

        try {
            // 최근 트레이스 목록
            final HttpRequestHandler indexHandler = new HttpRequestHandler() {
                @Override
                public void handle(ClassicHttpRequest req, ClassicHttpResponse res, HttpContext ctx)
                        throws IOException {
                    if (!Method.GET.isSame(req.getMethod())) {
                        res.setCode(405);
                        res.setEntity(textPlain("method not allowed"));
                        return;
                    }

                    // 쿼리 파라미터에서 limit 추출 (예: /?limit=200)
                    final String requestUri = req.getRequestUri();
                    final int qpos = requestUri.indexOf('?');
                    int limit = Conf.indexLimit(); // 기본값(설정 가능)
                    if (qpos >= 0 && qpos + 1 < requestUri.length()) {
                        final String[] pairs = requestUri.substring(qpos + 1).split("&");
                        for (String p : pairs) {
                            final int eq = p.indexOf('=');
                            if (eq > 0) {
                                final String k = p.substring(0, eq);
                                final String v = p.substring(eq + 1);
                                if (Q_LIMIT.equalsIgnoreCase(k)) {
                                    try {
                                        limit = Integer.parseInt(v);
                                    } catch (NumberFormatException ignored) {
                                        // 무시: 숫자 아님 → 기본값 유지
                                    }
                                }
                            }
                        }
                    }

                    // 안전 캡: 1 ~ maxTraces
                    limit = Math.max(1, Math.min(limit, Conf.maxTraces()));

                    final String html = renderIndexHtml(store, limit);
                    res.setCode(200);
                    res.setEntity(textHtml(html));
                }
            };

            // 헬스 체크
            final HttpRequestHandler healthHandler = new HttpRequestHandler() {
                @Override
                public void handle(ClassicHttpRequest req, ClassicHttpResponse res, HttpContext ctx)
                        throws IOException {
                    res.setCode(200);
                    res.setEntity(textPlain("ok"));
                }
            };

            // 파비콘(204, 캐시)
            final HttpRequestHandler faviconHandler = new HttpRequestHandler() {
                @Override
                public void handle(ClassicHttpRequest req, ClassicHttpResponse res, HttpContext ctx)
                        throws IOException {
                    res.setCode(204); // No Content
                    res.setEntity(null);
                    res.addHeader("Cache-Control", "public, max-age=31536000, immutable");
                }
            };

            // 상세(ASCII/HTML)
            final HttpRequestHandler traceHandler = new HttpRequestHandler() {
                @Override
                public void handle(ClassicHttpRequest req, ClassicHttpResponse res, HttpContext ctx)
                        throws IOException {
                    if (!Method.GET.isSame(req.getMethod())) {
                        res.setCode(405);
                        res.setEntity(textPlain("method not allowed"));
                        return;
                    }
                    // HttpCore 5: getRequestUri() = path + '?' + query, getPath() = path only
                    final String requestUri = req.getRequestUri(); // /trace/{id}?format=ascii
                    final int qpos = requestUri.indexOf('?');
                    final String pure = (qpos >= 0) ? requestUri.substring(0, qpos) : requestUri;
                    final String query = (qpos >= 0) ? requestUri.substring(qpos + 1) : null;

                    final String traceId = pure.startsWith(PATH_TRACE_PREFIX)
                            ? pure.substring(PATH_TRACE_PREFIX.length())
                            : "";
                    final String format = (query != null && query.startsWith(Q_FORMAT + "="))
                            ? query.substring((Q_FORMAT + "=").length())
                            : FORMAT_ASCII;

                    if (traceId.isEmpty()) {
                        res.setCode(400);
                        res.setEntity(textPlain("usage: /trace/{traceId}?format=ascii|html"));
                        return;
                    }

                    final List<SpanView> spans = store.getTrace(traceId);
                    if (spans.isEmpty()) {
                        res.setCode(404);
                        res.setEntity(textPlain("trace not found or expired"));
                        return;
                    }
                    Collections.sort(spans, new Comparator<SpanView>() {
                        @Override
                        public int compare(SpanView a, SpanView b) {
                            return Long.compare(a.startNanos, b.startNanos);
                        }
                    });

                    if (FORMAT_HTML.equalsIgnoreCase(format)) {
                        final String html = TraceDetailRenderer.renderHtml(traceId, spans); // ← 우측 패널 JS 포함 버전 사용
                        res.setCode(200);
                        res.setEntity(textHtml(html));
                    } else {
                        final String txt = TraceDetailRenderer.renderAscii(traceId, spans);
                        res.setCode(200);
                        res.setEntity(textPlain(txt));
                    }
                }
            };

            // 스팬 상세 JSON API: /api/span?traceId=...&spanId=...
            final HttpRequestHandler spanApiHandler = new HttpRequestHandler() {
                @Override
                public void handle(ClassicHttpRequest req, ClassicHttpResponse res, HttpContext ctx)
                        throws IOException {
                    if (!Method.GET.isSame(req.getMethod())) {
                        res.setCode(405);
                        res.setEntity(textPlain("method not allowed"));
                        return;
                    }
                    final String uri = req.getRequestUri();
                    String traceId = null, spanId = null;
                    final int q = uri.indexOf('?');
                    if (q >= 0 && q + 1 < uri.length()) {
                        final String[] pairs = uri.substring(q + 1).split("&");
                        for (String p : pairs) {
                            final int eq = p.indexOf('=');
                            if (eq > 0) {
                                final String k = p.substring(0, eq);
                                final String v = p.substring(eq + 1);
                                if (Q_TRACE_ID.equals(k))
                                    traceId = v;
                                else if (Q_SPAN_ID.equals(k))
                                    spanId = v;
                            }
                        }
                    }
                    if (traceId == null || spanId == null) {
                        res.setCode(400);
                        res.setEntity(json("{\"error\":\"missing traceId or spanId\"}"));
                        return;
                    }
                    final SpanDetail det = store.getDetail(traceId, spanId);
                    if (det == null) {
                        res.setCode(404);
                        res.setEntity(json("{\"error\":\"not found\"}"));
                        return;
                    }
                    res.setCode(200);
                    res.setEntity(json(det.toJson()));
                }
            };

            // 서비스/스팬 그래프(JSON): /api/trace/{id}/service-graph?mode=span&maxDepth=N
            final HttpRequestHandler serviceGraphHandler = new HttpRequestHandler() {
                @Override
                public void handle(ClassicHttpRequest req, ClassicHttpResponse res, HttpContext ctx)
                        throws IOException {
                    if (!Method.GET.isSame(req.getMethod())) {
                        res.setCode(405);
                        res.setEntity(textPlain("method not allowed"));
                        return;
                    }

                    final String uri = req.getRequestUri();
                    final int qmark = uri.indexOf('?');
                    final String purePath = (qmark >= 0) ? uri.substring(0, qmark) : uri;
                    final String query = (qmark >= 0) ? uri.substring(qmark + 1) : null;

                    if (!purePath.startsWith("/api/trace/") || !purePath.endsWith(SERVICE_GRAPH_SUFFIX)) {
                        res.setCode(400);
                        res.setEntity(json("{\"error\":\"bad path\"}"));
                        return;
                    }

                    final String traceId = purePath.substring("/api/trace/".length(),
                            purePath.length() - SERVICE_GRAPH_SUFFIX.length());
                    if (traceId.length() == 0) {
                        res.setCode(400);
                        res.setEntity(json("{\"error\":\"missing traceId\"}"));
                        return;
                    }

                    boolean spanMode = false; // 기본: 서비스↔서비스 그래프
                    Integer maxDepth = null; // 기본: 제한 없음
                    if (query != null && !query.isEmpty()) {
                        final String[] pairs = query.split("&");
                        for (String p : pairs) {
                            final int eq = p.indexOf('=');
                            final String k = (eq > 0) ? p.substring(0, eq) : p;
                            final String v = (eq > 0) ? p.substring(eq + 1) : "";
                            if (Q_MODE.equalsIgnoreCase(k)) {
                                // mode=span 이면 "스팬 단위 관계 그래프"
                                spanMode = MODE_SPAN.equalsIgnoreCase(v);
                            } else if (Q_MAX_DEPTH.equalsIgnoreCase(k)) {
                                try {
                                    maxDepth = Integer.valueOf(v);
                                    if (maxDepth != null && maxDepth < 0)
                                        maxDepth = 0; // 하한 보정
                                } catch (Exception ignore) {
                                    // 무시: 잘못된 값이면 전체
                                }
                            }
                        }
                    }

                    final List<SpanView> spans = store.getTrace(traceId);
                    if (spans == null || spans.isEmpty()) {
                        res.setCode(404);
                        res.setEntity(json("{\"error\":\"trace not found or expired\"}"));
                        return;
                    }

                    // maxDepth 필터 (동일 BFS 로직)
                    List<SpanView> filtered = spans;
                    if (maxDepth != null) {
                        // spanId -> SpanView
                        final java.util.Map<String, SpanView> byId = new java.util.HashMap<String, SpanView>(
                                spans.size() * 2);
                        for (SpanView s : spans) {
                            if (s != null && s.spanId != null)
                                byId.put(s.spanId, s);
                        }
                        // parent -> children
                        final java.util.Map<String, java.util.List<String>> children = new java.util.HashMap<String, java.util.List<String>>();
                        for (SpanView s : spans) {
                            if (s == null)
                                continue;
                            final String p = s.parentSpanId;
                            if (p == null || !byId.containsKey(p))
                                continue;
                            java.util.List<String> list = children.get(p);
                            if (list == null) {
                                list = new java.util.ArrayList<String>();
                                children.put(p, list);
                            }
                            list.add(s.spanId);
                        }
                        // BFS: 루트(부모 없거나 미보유)
                        final java.util.Map<String, Integer> depthById = new java.util.HashMap<String, Integer>(
                                spans.size() * 2);
                        final java.util.ArrayDeque<String> q = new java.util.ArrayDeque<String>();
                        for (SpanView s : spans) {
                            if (s == null || s.spanId == null)
                                continue;
                            final String p = s.parentSpanId;
                            if (p == null || !byId.containsKey(p)) {
                                depthById.put(s.spanId, 0);
                                q.addLast(s.spanId);
                            }
                        }
                        while (!q.isEmpty()) {
                            final String pid = q.removeFirst();
                            final Integer base = depthById.get(pid);
                            final java.util.List<String> ch = children.get(pid);
                            if (ch == null)
                                continue;
                            for (String cid : ch) {
                                if (!depthById.containsKey(cid)) {
                                    depthById.put(cid, base.intValue() + 1);
                                    q.addLast(cid);
                                }
                            }
                        }
                        // 고아/사이클 보정
                        for (SpanView s : spans) {
                            if (s != null && s.spanId != null && !depthById.containsKey(s.spanId)) {
                                depthById.put(s.spanId, 0);
                            }
                        }
                        // 필터링
                        final java.util.ArrayList<SpanView> tmp = new java.util.ArrayList<SpanView>(spans.size());
                        for (SpanView s : spans) {
                            if (s == null || s.spanId == null)
                                continue;
                            final Integer d = depthById.get(s.spanId);
                            if (d != null && d.intValue() <= maxDepth.intValue())
                                tmp.add(s);
                        }
                        filtered = tmp;
                    }

                    // 그래프 생성 (필터링된 스팬 사용)
                    final ServiceGraph graph = spanMode
                            ? ServiceGraphBuilder.buildSpanGraph(filtered) // 스팬 그래프(부모→자식)
                            : new ServiceGraphBuilder().build(filtered); // 기존 서비스 그래프

                    // JSON 직렬화
                    final StringBuilder sb = new StringBuilder(512);
                    sb.append("{\"nodes\":[");
                    for (int i = 0; i < graph.nodes.size(); i++) {
                        final ServiceGraph.Node n = graph.nodes.get(i);
                        sb.append("{\"id\":\"").append(jesc(n.id)).append("\",")
                                .append("\"label\":\"").append(jesc(n.label)).append("\",")
                                .append("\"spanCount\":").append(n.spanCount).append("}");
                        if (i < graph.nodes.size() - 1)
                            sb.append(",");
                    }
                    sb.append("],\"edges\":[");
                    for (int i = 0; i < graph.edges.size(); i++) {
                        final ServiceGraph.Edge e = graph.edges.get(i);
                        sb.append("{\"id\":\"").append(jesc(e.id)).append("\",")
                                .append("\"source\":\"").append(jesc(e.source)).append("\",")
                                .append("\"target\":\"").append(jesc(e.target)).append("\",")
                                .append("\"calls\":").append(e.calls).append(",")
                                .append("\"avgDurationMs\":")
                                .append(String.format(Locale.ROOT, "%.1f", e.avgDurationMs))
                                .append("}");
                        if (i < graph.edges.size() - 1)
                            sb.append(",");
                    }
                    sb.append("]}");

                    res.setCode(200);
                    res.setEntity(json(sb.toString()));
                }
            };

            // 서버 시작
            final HttpServer server = ServerBootstrap.bootstrap()
                    .setListenerPort(port)
                    .setLocalAddress(InetAddress.getByName(bind))
                    .setCanonicalHostName(Conf.canonicalHost())
                    .register(PATH_JS_PREFIX, new StaticClasspathHandler("/static"))
                    .register(PATH_CSS_PREFIX, new StaticClasspathHandler("/static"))
                    .register(PATH_ROOT, indexHandler)
                    .register(PATH_FAVICON, faviconHandler)
                    .register(PATH_HEALTHZ, healthHandler)
                    .register(PATH_TRACE_PREFIX + "*", traceHandler)
                    .register(PATH_API_SPAN, spanApiHandler)
                    .register(PATH_API_TRACE_PREFIX, serviceGraphHandler)
                    .create();
            server.start();

            System.out.printf("[trace-ui] http://%s:%d/ (maxTraces=%d ttl=%ds maxSpans/trace=%d)%n",
                    bind, port, Conf.maxTraces(), Conf.ttlSeconds(), Conf.maxSpansPerTrace());
        } catch (Exception e) {
            System.err.println("[trace-ui] failed to start http server: " + e);
        }
    }

    // ────────────────────────────── 정적 리소스 핸들러 ──────────────────────────────
    private static final class StaticClasspathHandler implements HttpRequestHandler {
        private final String basePath; // 예: "/static"

        StaticClasspathHandler(String basePath) {
            if (basePath == null || basePath.isEmpty())
                basePath = "/static";
            String p = basePath;
            if (!p.startsWith("/"))
                p = "/" + p;
            if (p.endsWith("/"))
                p = p.substring(0, p.length() - 1);
            this.basePath = p;
        }

        @Override
        public void handle(ClassicHttpRequest req, ClassicHttpResponse res, HttpContext ctx) throws IOException {
            if (!Method.GET.isSame(req.getMethod())) {
                res.setCode(405);
                res.setEntity(new StringEntity("method not allowed", ContentType.TEXT_PLAIN));
                return;
            }

            final String path = req.getPath(); // 예: "/js/cytoscape.min.js"
            if (path == null || path.isEmpty() || path.contains("..")) {
                res.setCode(400);
                res.setEntity(new StringEntity("bad path", ContentType.TEXT_PLAIN));
                return;
            }

            final String resourcePath = this.basePath + path; // "/static/js/cytoscape.min.js"

            try (InputStream in = StaticClasspathHandler.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    res.setCode(404);
                    res.setEntity(new StringEntity("not found: " + resourcePath, ContentType.TEXT_PLAIN));
                    return;
                }
                final byte[] bytes = readAll(in);

                res.setCode(200);
                res.addHeader("Cache-Control", "public, max-age=86400, immutable");
                res.setEntity(new ByteArrayEntity(bytes, guessContentType(path)));
            }
        }

        private static byte[] readAll(InputStream in) throws IOException {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            final byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }

        private static ContentType guessContentType(String path) {
            final String p = path.toLowerCase(Locale.ROOT);
            if (p.endsWith(".js") || p.endsWith(".mjs"))
                return ContentType.create("application/javascript", StandardCharsets.UTF_8);
            if (p.endsWith(".css"))
                return ContentType.create("text/css", StandardCharsets.UTF_8);
            if (p.endsWith(".json") || p.endsWith(".map"))
                return ContentType.APPLICATION_JSON;
            if (p.endsWith(".svg"))
                return ContentType.create("image/svg+xml");
            if (p.endsWith(".png"))
                return ContentType.create("image/png");
            if (p.endsWith(".jpg") || p.endsWith(".jpeg"))
                return ContentType.create("image/jpeg");
            return ContentType.DEFAULT_BINARY;
        }
    }

    /** 최근 트레이스 목록 간단 HTML (동일 출력) */
    private static String renderIndexHtml(TraceStore store, int limit) {
        final List<TraceStore.TraceSummary> recent = store.listRecent(limit);
        final StringBuilder html = new StringBuilder(1024);
        html.append("<!doctype html><html><head><meta charset='utf-8'>")
                .append("<title>OTel Trace UI</title>")
                .append("<style>")
                .append("body{font-family:system-ui,Segoe UI,Arial,sans-serif;padding:16px}")
                .append("table{border-collapse:collapse;width:100%}")
                .append("th,td{border:1px solid #ddd;padding:6px}")
                .append("th{background:#f5f5f5}")
                .append("code{background:#f2f2f2;padding:2px 4px;border-radius:4px}")
                .append("a{text-decoration:none;color:#0366d6}")
                .append("tr.good{background:#f0fdf4;}") // 연한 초록
                .append("tr.error{background:#fee2e2;}") // 연한 빨강
                .append("td.status-good{color:#15803d;font-weight:600;}") // 진한 초록 글씨
                .append("td.status-error{color:#b91c1c;font-weight:600;}") // 진한 빨강 글씨
                .append("</style>")
                .append("</head><body><h2>Recent Traces</h2>")
                .append("<table><thead><tr>")
                .append("<th>Start Time</th><th>Status</th><th>Trace ID</th>")
                .append("<th>Service</th><th>Trace Name</th><th>Duration</th>")
                .append("<th>Spans</th><th>view</th>")
                .append("</tr></thead><tbody>");

        for (TraceStore.TraceSummary s : recent) {
            String rowClass = s.error ? "error" : "good";
            String statusClass = s.error ? "status-error" : "status-good";

            html.append("<tr class='").append(rowClass).append("'>")
                    .append("<td>").append(esc(s.startTimeReadable)).append("</td>")
                    // 상태 컬럼
                    .append("<td class='").append(statusClass).append("'>").append(s.status).append("</td>")
                    .append("<td><code>").append(esc(s.traceId)).append("</code></td>")
                    .append("<td>").append(esc(s.service)).append("</td>")
                    .append("<td>").append(esc(s.rootName)).append("</td>")
                    .append("<td>").append(s.durationMsStr()).append("</td>")
                    .append("<td>").append(s.spanCount).append("</td>")
                    .append("<td><a href='/trace/").append(esc(s.traceId)).append("?format=ascii'>ascii</a>")
                    .append(" | <a href='/trace/").append(esc(s.traceId)).append("?format=html'>html</a></td>")
                    .append("</tr>");
        }

        html.append("</tbody></table>")
                .append("<p style='margin-top:10px;color:#666'>Try: <code>/trace/{traceId}?format=ascii</code></p>")
                .append("</body></html>");
        return html.toString();
    }

    // ── 공통 유틸 (로직 동일, 위치만 정리) ────────────────────────────────────

    private static StringEntity textPlain(String s) {
        return new StringEntity(s, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
    }

    private static StringEntity textHtml(String s) {
        return new StringEntity(s, ContentType.TEXT_HTML.withCharset(StandardCharsets.UTF_8));
    }

    private static StringEntity json(String s) {
        return new StringEntity(s, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8));
    }

    private static String esc(String s) {
        return (s == null) ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String jesc(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 시스템 속성 파서 (동일 로직) */
    static final class Conf {
        private static volatile String bind = "0.0.0.0";
        private static volatile int port = 55679;
        private static volatile int maxTraces = 64;
        private static volatile int maxSpansPerTrace = 2048;
        private static volatile int ttlSeconds = 600;
        private static volatile String canonicalHost = "localhost";
        private static volatile int indexLimit = 100;

        static void init(io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties cfg) {
            bind = firstNonEmpty(
                    System.getProperty("otel.javaagent.extension.traceui.bind"),
                    cfg.getString("otel.javaagent.extension.traceui.bind"),
                    "0.0.0.0");
            port = firstInt("otel.javaagent.extension.traceui.port", cfg, 55679);
            maxTraces = firstInt("otel.javaagent.extension.traceui.maxTraces", cfg, 64);
            maxSpansPerTrace = firstInt("otel.javaagent.extension.traceui.maxSpansPerTrace", cfg, 2048);
            ttlSeconds = firstInt("otel.javaagent.extension.traceui.ttlSeconds", cfg, 600);
            canonicalHost = firstNonEmpty(
                    System.getProperty("otel.javaagent.extension.traceui.host"),
                    cfg.getString("otel.javaagent.extension.traceui.host"),
                    "localhost");
            indexLimit = firstInt("otel.javaagent.extension.traceui.indexLimit", cfg, 100);
        }

        private static String firstNonEmpty(String a, String b, String def) {
            if (a != null && !a.isEmpty())
                return a;
            if (b != null && !b.isEmpty())
                return b;
            return def;
        }

        private static int firstInt(String key, io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties cfg, int def) {
            final String s = System.getProperty(key);
            if (s != null) {
                try {
                    return Integer.parseInt(s);
                } catch (Exception ignored) {
                }
            }
            final Integer v = cfg.getInt(key);
            return v != null ? v : def;
        }

        static String bindAddress() {
            return bind;
        }

        static int port() {
            return port;
        }

        static int maxTraces() {
            return maxTraces;
        }

        static int maxSpansPerTrace() {
            return maxSpansPerTrace;
        }

        static int ttlSeconds() {
            return ttlSeconds;
        }

        static String canonicalHost() {
            return canonicalHost;
        }

        static int indexLimit() {
            return indexLimit;
        }
    }
}
