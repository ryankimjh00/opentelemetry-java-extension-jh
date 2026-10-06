package org.example.otel.mdc;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.MDC;

/**
 * Spring의 HandlerInterceptor를 인터셉트하여 MDC 데이터를 OpenTelemetry Span에 추가하는 Instrumentation 클래스.
 */
public class MDCInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return ElementMatchers.hasSuperType(ElementMatchers.named("org.springframework.web.servlet.HandlerInterceptor"))
                .and(ElementMatchers.not(ElementMatchers.isInterface()));
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                ElementMatchers.named("preHandle").and(ElementMatchers.takesArguments(3)),
                MDCLoggingAdvice.class.getName()
        );
    }

    /**
     * `preHandle` 메서드 실행 시 MDC에서 데이터를 추출하여 OpenTelemetry Span에 추가하는 Advice 클래스.
     */
    private static class MDCLoggingAdvice {
        private static final ContextKey<String> TRANSACTION_ID_KEY = ContextKey.named("transactionId");

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter() {
            // 현재 실행 중인 OpenTelemetry Span 가져오기
            Span currentSpan = Span.current();
            if (currentSpan != null) {
                // MDC에서 transactionId 가져오기
                String transactionId = MDC.get("transactionId");

                if (transactionId != null && !transactionId.isEmpty()) {
                    // OpenTelemetry Span 속성으로 transactionId 추가
                    currentSpan.setAttribute("log.mdc.transactionId", transactionId);
                }
            }
        }
    }
}
