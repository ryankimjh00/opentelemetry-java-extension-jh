package org.example.otel.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TraceStartTimeSpanProcessor implements SpanProcessor {
    private final ConcurrentHashMap<String, Long> traceStartTimes = new ConcurrentHashMap<>();

    // 사람이 읽을 수 있도록 포맷팅할 포맷터
    private static final DateTimeFormatter READABLE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS");

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        String traceId = span.getSpanContext().getTraceId();
        span.setAttribute("trace.id", traceId);

        /*
         * ──────────────────────────────────────────────────────────
         * 1) Context에서 부모 Span을 꺼내서 유효성 검사하기
         * - parentContext에 Span이 없으면, 루트 스팬이다.
         * ──────────────────────────────────────────────────────────
         */
        Span parentSpan = Span.fromContext(parentContext);
        boolean isRootSpan = !parentSpan.getSpanContext().isValid();
        span.setAttribute("is.root.span", isRootSpan);

        /*
         * ──────────────────────────────────────────────────────────
         * 2) (선택) SpanData를 통해 부모 SpanContext 직접 가져오기
         * - toSpanData().getParentSpanContext()로도 확인할 수 있다.
         * ──────────────────────────────────────────────────────────
         */
        SpanContext parentContextInfo = span.toSpanData().getParentSpanContext();
        boolean isRootViaData = !parentContextInfo.isValid();
        // (둘 중 편한 방법 하나만 사용하시면 됩니다)
        span.setAttribute("is.root.viaData", isRootViaData);

        // 최초 시작 시각(ms)를 계산해서 attribute로 붙이고
        long startNanos = span.toSpanData().getStartEpochNanos();
        long startMillis = TimeUnit.NANOSECONDS.toMillis(
                traceStartTimes.computeIfAbsent(traceId, id -> startNanos));
        span.setAttribute("trace.start.time", startMillis);

        // 사람이 보기 좋은 형식으로 추가 포맷팅
        ZonedDateTime zdt = Instant.ofEpochMilli(startMillis)
                .atZone(ZoneId.systemDefault());
        String readable = READABLE_FORMATTER.format(zdt);
        span.setAttribute("trace.start.time.readable", readable);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
        /* no-op */ }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    @Override
    public CompletableResultCode shutdown() {
        traceStartTimes.clear();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }
}
