package org.example.otel.traceui;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * 완료된 스팬을 SpanView로 변환하여 TraceStore에 저장하는 경량 SpanProcessor.
 * - HTTP 메서드/상태코드는 최신/구식 키 모두 지원
 * - status_code는 숫자(long) 우선, 없으면 문자열 파싱
 */
final class InMemorySpanProcessor implements SpanProcessor {

    private final TraceStore store;

    InMemorySpanProcessor(TraceStore store) {
        this.store = store;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // 시작 시엔 아무 것도 안 함 (오버헤드 최소화)
    }

    public void onEnd(ReadableSpan span) {
        final SpanData d = span.toSpanData();
        final Attributes attrs = d.getAttributes();

        final String service = orEmpty(
                d.getResource().getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.name")));
        final String httpMethod = coalesce(
                attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.method")),
                attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("http.method")));
        final Integer httpStatus = extractHttpStatus(attrs);

        // ✅ Processor(TraceStartTimeSpanProcessor) 가 심어준 trace.start.time(ms) 우선
        Long traceStartMsAttr = attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("trace.start.time"));
        long traceStartMillis = (traceStartMsAttr != null && traceStartMsAttr > 0L)
                ? traceStartMsAttr
                : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(d.getStartEpochNanos()); // 폴백

        // ✅ startNanos/endNanos 는 현재 epoch-ns 를 넣고 있으므로 duration 은 정상
        // (만약 trace-clock 값이 따로 있다면 그걸 넣어도 OK. 둘이 같은 기준이면 됩니다.)
        SpanView view = new SpanView(
                d.getTraceId(),
                d.getSpanId(),
                d.getParentSpanId(),
                d.getName(),
                service,
                d.getStartEpochNanos(), // startNanos
                d.getEndEpochNanos(), // endNanos
                httpMethod,
                httpStatus,
                null, // events (필요시 채우기)
                d.getStartEpochNanos(), // ✅ startEpochNanos
                traceStartMillis // ✅ traceStartMillis
        );
        store.add(view);

        // ▼ 상세(SpanDetail)는 기존대로
        SpanDetail det = new SpanDetail(
                d.getTraceId(),
                d.getSpanId(),
                d.getParentSpanId(),
                d.getName(),
                service,
                d.getKind() == null ? "" : d.getKind().name(),
                d.getStatus() == null ? "" : d.getStatus().getStatusCode().name(),
                d.getStatus() == null ? "" : orEmpty(d.getStatus().getDescription()),
                d.getStartEpochNanos(),
                d.getEndEpochNanos());
        d.getAttributes().forEach((k, v) -> det.attributes.put(k.getKey(), String.valueOf(v)));
        d.getResource().getAttributes().forEach((k, v) -> det.resource.put(k.getKey(), String.valueOf(v)));
        if (d.getInstrumentationScopeInfo() != null) {
            det.scope.put("name", orEmpty(d.getInstrumentationScopeInfo().getName()));
            det.scope.put("version", orEmpty(d.getInstrumentationScopeInfo().getVersion()));
            det.scope.put("schemaUrl", orEmpty(d.getInstrumentationScopeInfo().getSchemaUrl()));
        }
        det.eventCount = d.getEvents() == null ? 0 : d.getEvents().size();
        det.linkCount = d.getLinks() == null ? 0 : d.getLinks().size();
        store.putDetail(d.getTraceId(), det);
    }

    /** Attributes에서 HTTP 상태코드 추출: long → Integer, 실패 시 문자열 파싱 */
    private static Integer extractHttpStatus(Attributes attrs) {
        // 1) 숫자 키 우선 (최신 → 구식)
        Long v = attrs.get(AttributeKey.longKey("http.response.status_code"));
        if (v == null)
            v = attrs.get(AttributeKey.longKey("http.status_code"));
        if (v != null)
            return v.intValue();

        // 2) 문자열 키 폴백 (최신 → 구식)
        String s = attrs.get(AttributeKey.stringKey("http.response.status_code"));
        if (s == null)
            s = attrs.get(AttributeKey.stringKey("http.status_code"));
        if (s != null) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException ignore) {
            }
        }
        return null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : b;
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }
}
