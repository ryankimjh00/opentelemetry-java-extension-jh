package org.example.otel.traceui;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 트레이스 메모리 저장소(스레드 안전).
 * - 트레이스별 스팬 목록 보관 (완료 스팬만)
 * - LRU + TTL 기반 정리
 * - 트레이스별 최대 스팬 수 제한
 */
final class TraceStore {

    private final int maxTraces;
    private final int maxSpansPerTrace;
    private final long ttlNanos;

    private final ConcurrentHashMap<String, TraceEntry> traces = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> lru = new ConcurrentLinkedDeque<>();

    TraceStore(int maxTraces, int maxSpansPerTrace, Duration ttl) {
        this.maxTraces = Math.max(4, maxTraces);
        this.maxSpansPerTrace = Math.max(32, maxSpansPerTrace);
        this.ttlNanos = Math.max(ttl.toNanos(), 30_000_000_000L); // 최소 30s
    }

    void add(SpanView span) {
        long now = System.nanoTime();
        // TTL 정리(가볍게, 확률적): 1/N 확률로 수행해 오버헤드 최소화
        if ((now & 0xFF) == 0) {
            sweep(now);
        }

        TraceEntry entry = traces.computeIfAbsent(span.traceId, new java.util.function.Function<String, TraceEntry>() {
            @Override
            public TraceEntry apply(String id) {
                return new TraceEntry();
            }
        });
        synchronized (entry) {
            entry.lastTouchedNanos = now;

            // 트레이스 스팬 목록 추가(초과분은 앞쪽부터 제거)
            if (entry.spans.size() >= maxSpansPerTrace) {
                int removeCount = Math.max(1, maxSpansPerTrace / 8);
                for (int i = 0; i < removeCount && !entry.spans.isEmpty(); i++) {
                    entry.spans.removeFirst();
                }
            }
            entry.spans.addLast(span);
        }

        // LRU 갱신: 중복 제거 후 뒤로 밀기
        lru.remove(span.traceId);
        lru.addLast(span.traceId);

        // 용량 초과 시 LRU 트레이스 제거
        while (traces.size() > maxTraces) {
            String victim = lru.pollFirst();
            if (victim == null)
                break;
            traces.remove(victim);
        }
    }


    List<SpanView> getTrace(String traceId) {
        TraceEntry e = traces.get(traceId);
        if (e == null)
            return Collections.emptyList();
        synchronized (e) {
            return new ArrayList<SpanView>(e.spans); // 복사본 반환
        }
    }

    /** SpanDetail 기준 에러 여부 판정 */
    private static boolean isError(SpanDetail det) {
        if (det == null)
            return false;

        // 1) OTel StatusCode
        // - InMemorySpanProcessor 에서 det.statusCode =
        // d.getStatus().getStatusCode().name() 로 세팅
        if (det.statusCode != null && "ERROR".equalsIgnoreCase(det.statusCode)) {
            return true;
        }

        // 2) HTTP 5xx
        int http = parseHttpStatusFromDetail(det);
        if (http >= 500)
            return true;

        // 3) 예외 속성 힌트 (선택)
        if (det.attributes != null) {
            if (det.attributes.containsKey("exception.type")
                    || det.attributes.containsKey("exception.message")) {
                return true;
            }
            // OpenTelemetry status attribute가 맵에 있는 경우 대비
            String otelStatus = det.attributes.get("otel.status_code");
            if (otelStatus != null && "ERROR".equalsIgnoreCase(otelStatus))
                return true;
        }
        return false;
    }

    private static int parseHttpStatusFromDetail(SpanDetail det) {
        if (det == null || det.attributes == null)
            return -1;
        // InMemorySpanProcessor 에서 문자열로 넣었으므로 String 파싱
        String s = det.attributes.get("http.response.status_code");
        if (s == null)
            s = det.attributes.get("http.status_code");
        if (s != null) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
            }
        }
        return -1;
    }

    List<TraceSummary> listRecent(int limit) {
        ArrayList<TraceSummary> out = new ArrayList<TraceSummary>();
        int count = 0;
        java.util.Iterator<String> it = lru.descendingIterator();

        while (it.hasNext() && count < limit) {
            String id = it.next();
            List<SpanView> spans = getTrace(id);
            if (spans.isEmpty())
                continue;

            IdSet parentSet = buildIdSet(spans);
            SpanView root = null;
            for (SpanView s : spans) {
                String p = s.parentSpanId;
                boolean noParent = (p == null || p.isEmpty());
                if (noParent || !parentSet.contains(p)) {
                    if (root == null || s.startEpochNanos < root.startEpochNanos) {
                        root = s;
                    }
                }
            }

            SpanView last = spans.get(spans.size() - 1);

            // duration: epoch-ns 기준으로 통일
            long anchorStartEpochNs = (root != null ? root.startEpochNanos : last.startEpochNanos);
            long endEpochNs = last.endNanos; // 수집부에서 epoch-ns 넣었으므로 동일 도메인
            long durationUs = Math.max(0, (endEpochNs - anchorStartEpochNs) / 1_000L);

            // 시작시각(ms): trace.start.time 우선, 없으면 startEpochNanos 사용
            long startEpochMs = 0L;
            if (root != null) {
                if (root.traceStartMillis > 0) {
                    startEpochMs = root.traceStartMillis;
                } else if (root.startEpochNanos > 0) {
                    startEpochMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(root.startEpochNanos);
                }
            } else if (anchorStartEpochNs > 0) {
                startEpochMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(anchorStartEpochNs);
            }

            int spanCount = spans.size();
            boolean hasError = false;
            for (SpanView s : spans) {
                SpanDetail d = getDetail(id, s.spanId);
                if (isError(d)) { // <-- 이전의 d.status 사용 부분을 이 한 줄로 교체
                    hasError = true;
                    break;
                }
            }

            out.add(new TraceSummary(
                    id,
                    (root != null ? root.name : "(root?)"),
                    (root != null ? root.service : ""),
                    durationUs,
                    startEpochMs,
                    spanCount,
                    hasError));
            count++;

        }
        return out;
    }

    // ★ 반환 타입을 IdSet으로 수정
    private static IdSet buildIdSet(List<SpanView> spans) {
        HashSet<String> ids = new HashSet<String>(spans.size() * 2);
        for (SpanView s : spans)
            ids.add(s.spanId);
        return new IdSet(ids);
    }


    // 내부 클래스들

    private static final class TraceEntry {
        final ArrayDeque<SpanView> spans = new ArrayDeque<SpanView>();
        final HashMap<String, SpanDetail> details = new HashMap<String, SpanDetail>(); // ★ 추가
        volatile long lastTouchedNanos = System.nanoTime();
    }

    void putDetail(String traceId, SpanDetail detail) {
        TraceEntry entry = traces.computeIfAbsent(traceId, id -> new TraceEntry());
        synchronized (entry) {
            entry.lastTouchedNanos = System.nanoTime();
            entry.details.put(detail.spanId, detail);
        }
        // LRU 갱신
        lru.remove(traceId);
        lru.addLast(traceId);
    }

    SpanDetail getDetail(String traceId, String spanId) {
        TraceEntry e = traces.get(traceId);
        if (e == null)
            return null;
        synchronized (e) {
            return e.details.get(spanId);
        }
    }

    private void sweep(long now) {
        long expireBefore = now - ttlNanos;
        for (Map.Entry<String, TraceEntry> ent : traces.entrySet()) {
            TraceEntry e = ent.getValue();
            if (e.lastTouchedNanos < expireBefore) {
                if (traces.remove(ent.getKey(), e)) {
                    lru.remove(ent.getKey());
                }
            }
        }
    }

    static final class TraceSummary {
        final String traceId;
        final String rootName;
        final String service;
        final long durationUs;

        // ▼ 추가: 인덱스 표에 노출할 시작시각 (ms)과 포맷 문자열
        final long startEpochMillis;
        final String startTimeReadable;

        // ▼ 추가: 스팬 개수 / 오류 여부
        final int spanCount;
        final boolean error;
        final String status;

        TraceSummary(String traceId, String rootName, String service,
                long durationUs, long startEpochMillis,
                int spanCount, boolean error) {
            this.traceId = traceId;
            this.rootName = rootName;
            this.service = service;
            this.durationUs = durationUs;
            this.startEpochMillis = startEpochMillis;
            this.spanCount = spanCount;
            this.error = error;
            this.status = error ? "Error" : "Good";

            // 사람이 읽기 쉬운 시간 포맷
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            this.startTimeReadable = startEpochMillis > 0
                    ? f.format(new java.util.Date(startEpochMillis))
                    : "—";
        }

        /** "xxx.xxxms" */
        String durationMsStr() {
            return String.format("%.3fms", durationUs / 1000.0);
        }
    }

    /** HashSet 래퍼: 멤버십 확인에만 사용. equals/hashCode 제한으로 실수 사용 방지. */
    private static final class IdSet {
        private final HashSet<String> set;

        IdSet(HashSet<String> set) {
            this.set = set;
        }

        boolean contains(String s) {
            return set.contains(s);
        }
    }
}
