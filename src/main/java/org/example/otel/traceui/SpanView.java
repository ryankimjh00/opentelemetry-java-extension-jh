package org.example.otel.traceui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 뷰어 출력에 필요한 최소 필드만 가진 경량 DTO.
 * - 메모리 사용 최소화를 위해 문자열/원시 필드 위주 구성
 * - duration 계산은 trace-clock 기준의 startNanos/endNanos 사용
 * - 시작 시각 표시(인덱스 표)는 epoch 기반 필드를 사용(있을 때만)
 */
final class SpanView {
    final String traceId;
    final String spanId;
    final String parentSpanId;
    final String name;
    final String service;

    /** trace-clock 기준(ns). duration 계산용. */
    final long startNanos;
    final long endNanos;

    /** HTTP 메타(옵션) */
    final String httpMethod; // nullable
    final Object httpStatusCode; // nullable (OTel 속성 타입 혼재 대비)

    /** 스팬 이벤트(없으면 empty, 불변 리스트) */
    final List<EventView> events;

    /** ▼ 추가: 절대시간 기반 시작시각 (있으면 표시용으로 사용) */
    /** OTel SpanData#getStartEpochNanos 값을 그대로 저장 (ns). 없으면 -1 */
    final long startEpochNanos;
    /** SpanProcessor가 세팅한 trace.start.time(ms). 없으면 -1 */
    final long traceStartMillis;

    /** 기존 생성자(하위호환): 절대시간 정보가 없을 때 사용 */
    SpanView(String traceId, String spanId, String parentSpanId, String name,
            String service, long startNanos, long endNanos,
            String httpMethod, Object httpStatusCode) {
        this(traceId, spanId, parentSpanId, name, service, startNanos, endNanos,
                httpMethod, httpStatusCode, null, -1L, -1L);
    }

    /** 기존 + 이벤트 포함(하위호환) */
    SpanView(String traceId, String spanId, String parentSpanId, String name,
            String service, long startNanos, long endNanos,
            String httpMethod, Object httpStatusCode,
            List<EventView> events) {
        this(traceId, spanId, parentSpanId, name, service, startNanos, endNanos,
                httpMethod, httpStatusCode, events, -1L, -1L);
    }

    /**
     * 신규 생성자(권장): 절대시간(startEpochNanos/traceStartMillis)까지 함께 전달.
     * 
     * @param startEpochNanos  OTel epoch-ns (없으면 -1)
     * @param traceStartMillis Processor가 넣은 trace.start.time(ms) (없으면 -1)
     */
    SpanView(String traceId, String spanId, String parentSpanId, String name,
            String service, long startNanos, long endNanos,
            String httpMethod, Object httpStatusCode,
            List<EventView> events,
            long startEpochNanos, long traceStartMillis) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId == null ? "" : parentSpanId;
        this.name = name;
        this.service = service == null ? "" : service;
        this.startNanos = startNanos;
        this.endNanos = endNanos;
        this.httpMethod = httpMethod;
        this.httpStatusCode = httpStatusCode;

        if (events == null || events.isEmpty()) {
            this.events = Collections.emptyList();
        } else {
            this.events = Collections.unmodifiableList(new ArrayList<EventView>(events));
        }

        this.startEpochNanos = startEpochNanos;
        this.traceStartMillis = traceStartMillis;
    }

    /** 마이크로초 단위 지속시간(음수 방지). */
    long durationMicros() {
        long dur = (endNanos - startNanos) / 1_000L;
        return Math.max(dur, 0L);
    }

    /** "xxx.xxxms" 문자열. */
    String durationMsStr() {
        return String.format("%.3fms", durationMicros() / 1000.0);
    }

    /** 이벤트 존재 여부(렌더러에서 분기용). */
    boolean hasEvents() {
        return events != null && !events.isEmpty();
    }

    /** 스팬 상의 단일 이벤트(최소 표현). */
    static final class EventView {
        /** 이벤트 시점(ns) – trace clock 기준(= SpanView.startNanos와 동일 기준). */
        final long timeNanos;
        /** 이벤트 이름(툴팁/우측 패널 표시에 사용). */
        final String name;

        public EventView(long timeNanos, String name) {
            this.timeNanos = timeNanos;
            this.name = name == null ? "" : name;
        }
    }
}
