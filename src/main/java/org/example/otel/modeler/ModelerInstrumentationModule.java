package org.example.otel.modeler;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Collections;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class ModelerInstrumentationModule extends InstrumentationModule {

    private static final Tracer tracer =
        GlobalOpenTelemetry.getTracer("modeler-instrumentation", "1.0.0");

    public ModelerInstrumentationModule() {
        super("modeler", "modeler-instrumentation");
        System.out.println("[ModelerInstrumentation] Module constructed");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        System.out.println("[ModelerInstrumentation] typeInstrumentations()");
        return Collections.singletonList(new ModelerTypeInstrumentation());
    }

    private static class ModelerTypeInstrumentation implements TypeInstrumentation {

        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            System.out.println("[ModelerInstrumentation] typeMatcher() called");
            return nameStartsWith("com.indigo.esb.modeler");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            System.out.println("[ModelerInstrumentation] transform() called");
            transformer.applyAdviceToMethod(
                // 생성자 제외한 모든 인스턴스 메서드
                any(),
                ModelerInstrumentationModule.class.getName() + "$ModelerAdvice"
            );
        }
    }

    public static class ModelerAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static SpanScope onEnter(@Advice.Origin("#t.#m") String method) {
            System.out.println("********************************************************[ModelerInstrumentation] ENTER  " + method);
            Span span = tracer.spanBuilder(method)
                              .setSpanKind(SpanKind.INTERNAL)
                              .startSpan();
            Scope scope = span.makeCurrent();
            return new SpanScope(span, scope);
        }


        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.Enter SpanScope spanScope,
                @Advice.Origin("#t.#m") String method,
                @Advice.Thrown Throwable error) {

            if (error != null) {
                System.err.println("[ModelerInstrumentation] ERROR  " + method + " -> " + error);
                spanScope.span.recordException(error);
                spanScope.span.setStatus(StatusCode.ERROR);
            }
            System.out.println("[ModelerInstrumentation] EXIT   " + method);
            spanScope.span.end();
            spanScope.scope.close();
        }
    }

    /**
     * Advice 간에 Span과 Scope를 전달하기 위한 단순 컨테이너
     */
    public static class SpanScope {
        final Span span;
        final Scope scope;
        public SpanScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }
}
