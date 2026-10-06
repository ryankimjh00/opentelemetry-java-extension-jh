package org.example.otel.traceui;

import java.util.*;
import java.util.Map.Entry;

/**
 * 클릭 시 우측(또는 하단)에 표시할 스팬 상세 DTO.
 * - 문자열 위주로 직렬화해 메모리 낭비를 줄임
 * - JSON 응답은 간단한 수기 직렬화(외부 lib 없이)
 */
final class SpanDetail {
    final String traceId;
    final String spanId;
    final String parentSpanId;
    final String name;
    final String service;
    final String kind;           // INTERNAL/SERVER/CLIENT/...
    final String statusCode;     // UNSET/OK/ERROR
    final String statusMessage;  // 상태 메시지(있으면)
    final long   startEpochNanos;
    final long   endEpochNanos;

    // 평면화된 속성들(문자열 값으로 보관)
    final LinkedHashMap<String,String> attributes   = new LinkedHashMap<String,String>();
    final LinkedHashMap<String,String> resource     = new LinkedHashMap<String,String>();
    final LinkedHashMap<String,String> scope        = new LinkedHashMap<String,String>();
    // (옵션) 이벤트/링크는 개수만 간단히
    int eventCount;
    int linkCount;

    SpanDetail(String traceId, String spanId, String parentSpanId,
               String name, String service, String kind,
               String statusCode, String statusMessage,
               long startEpochNanos, long endEpochNanos) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId == null ? "" : parentSpanId;
        this.name = name == null ? "" : name;
        this.service = service == null ? "" : service;
        this.kind = kind == null ? "" : kind;
        this.statusCode = statusCode == null ? "" : statusCode;
        this.statusMessage = statusMessage == null ? "" : statusMessage;
        this.startEpochNanos = startEpochNanos;
        this.endEpochNanos = endEpochNanos;
    }

    /** 매우 단순한 JSON 직렬화기 (외부 라이브러리 없이 사용) */
    String toJson() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{");
        kv(sb, "traceId", traceId).append(',');
        kv(sb, "spanId", spanId).append(',');
        kv(sb, "parentSpanId", parentSpanId).append(',');
        kv(sb, "name", name).append(',');
        kv(sb, "service", service).append(',');
        kv(sb, "kind", kind).append(',');
        kv(sb, "statusCode", statusCode).append(',');
        kv(sb, "statusMessage", statusMessage).append(',');
        sb.append("\"startEpochNanos\":").append(startEpochNanos).append(',');
        sb.append("\"endEpochNanos\":").append(endEpochNanos).append(',');
        obj(sb, "attributes", attributes).append(',');
        obj(sb, "resource", resource).append(',');
        obj(sb, "scope", scope).append(',');
        sb.append("\"eventCount\":").append(eventCount).append(',');
        sb.append("\"linkCount\":").append(linkCount);
        sb.append("}");
        return sb.toString();
    }

    private static StringBuilder kv(StringBuilder sb, String k, String v) {
        sb.append('"').append(esc(k)).append('"').append(':')
          .append('"').append(esc(v)).append('"');
        return sb;
    }
    private static StringBuilder obj(StringBuilder sb, String k, Map<String,String> map) {
        sb.append('"').append(esc(k)).append('"').append(':').append('{');
        boolean first = true;
        for (Entry<String,String> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            kv(sb, e.getKey(), e.getValue());
        }
        sb.append('}');
        return sb;
    }
    private static String esc(String s) {
        if (s == null) return "";
        // JSON 최소 이스케이프
        return s.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");
    }
}
