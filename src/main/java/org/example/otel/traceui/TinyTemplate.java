// src/main/java/org/example/otel/traceui/TinyTemplate.java
package org.example.otel.traceui;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TinyTemplate: 초경량 템플릿 엔진 (Java 8)
 *
 * 지원 문법
 *  - 스칼라 치환: {{var}}           → HTML 이스케이프 적용
 *  - RAW 치환   : {{{var}}}         → 이스케이프 없이 그대로 주입
 *  - 반복 섹션 : {{#list}}...{{/list}} (list는 List<Map<String,String>> 가정)
 *
 * 특징
 *  - 외부 라이브러리 없음, 문자열 치환만 수행
 *  - 단순하고 가독성 좋은 구현, 유지보수 용이
 */
final class TinyTemplate {

    // 섹션(반복)
    private static final Pattern SECTION   = Pattern.compile("\\{\\{#([a-zA-Z0-9_\\.]+)\\}\\}([\\s\\S]*?)\\{\\{/\\1\\}\\}");
    // RAW 변수 {{{var}}}
    private static final Pattern VAR_RAW   = Pattern.compile("\\{\\{\\{([a-zA-Z0-9_\\.]+)\\}\\}\\}");
    // SAFE 변수 {{var}}
    private static final Pattern VAR_SAFE  = Pattern.compile("\\{\\{([a-zA-Z0-9_\\.]+)\\}\\}");

    private TinyTemplate() {}

    /** 템플릿과 모델을 바인딩하여 최종 문자열 생성 */
    static String render(String template, Map<String, Object> model) {
        if (template == null) return "";
        String out = template;

        // 1) 반복 섹션 치환
        while (true) {
            Matcher m = SECTION.matcher(out);
            if (!m.find()) break;

            final String key  = m.group(1);
            final String body = m.group(2);
            final Object v    = model.get(key);

            StringBuilder sb = new StringBuilder(body.length() * 2);
            if (v instanceof Iterable) {
                for (Object item : (Iterable<?>) v) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String,String> row = (Map<String,String>) item;
                        sb.append(applyVars(body, row)); // row 스코프 치환
                    }
                }
            }
            out = out.substring(0, m.start()) + sb.toString() + out.substring(m.end());
        }

        // 2) 상위 스코프 치환
        return applyVars(out, toScalarMap(model));
    }

    /** 문자열 내 변수 치환( RAW → SAFE 순서 ) */
    private static String applyVars(String s, Map<String,String> scalars) {
        // RAW {{{var}}}
        Matcher mr = VAR_RAW.matcher(s);
        StringBuffer bufR = new StringBuffer(s.length() + 64);
        while (mr.find()) {
            String k = mr.group(1);
            String val = scalars.getOrDefault(k, "");
            mr.appendReplacement(bufR, Matcher.quoteReplacement(val));
        }
        mr.appendTail(bufR);
        String tmp = bufR.toString();

        // SAFE {{var}}
        Matcher ms = VAR_SAFE.matcher(tmp);
        StringBuffer bufS = new StringBuffer(tmp.length() + 64);
        while (ms.find()) {
            String k = ms.group(1);
            String val = html(scalars.get(k));
            ms.appendReplacement(bufS, Matcher.quoteReplacement(val));
        }
        ms.appendTail(bufS);
        return bufS.toString();
    }

    /** 모델에서 스칼라만 추출 */
    private static Map<String,String> toScalarMap(Map<String,Object> model) {
        Map<String,String> out = new HashMap<String,String>();
        for (Map.Entry<String,Object> e : model.entrySet()) {
            Object v = e.getValue();
            if (v == null) { out.put(e.getKey(), ""); continue; }
            if (v instanceof Map || v instanceof Iterable) continue;
            out.put(e.getKey(), String.valueOf(v));
        }
        return out;
    }

    /** HTML 이스케이프 */
    private static String html(String s) {
        if (s == null) return "";
        String r = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        r = r.replace("\"","&quot;").replace("'","&#39;");
        return r;
    }
}
