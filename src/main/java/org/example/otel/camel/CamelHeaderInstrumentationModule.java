package org.example.otel.camel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.camel.Exchange;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * CamelHeaderInstrumentationModule는 Apache Camel의 모든 Processor 구현체의 process(Exchange)
 * 메서드를 인터셉트하여, Camel In 메시지의 헤더 중 "Camel"으로 시작하는 항목들을 현재 활성 스팬의 속성(attribute)으로 추가합니다.
 *
 * 이 모듈은 기존의 Camel instrumentation과 독립적으로 동작하며, -Dotel.javaagent.extensions 옵션을 통해 agent에 등록됩니다.
 */
public class CamelHeaderInstrumentationModule extends InstrumentationModule {

  public CamelHeaderInstrumentationModule() {
    super("camel-header", "camel-header-extraction");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    // Processor 인터페이스를 구현한 모든 클래스가 대상입니다.
    return Collections.singletonList(new CamelProcessorInstrumentation());
  }

  /**
   * CamelProcessorInstrumentation은 org.apache.camel.Processor를 구현한 모든 클래스의
   * process(Exchange) 메서드를 타겟으로 Advice를 적용합니다.
   */
  public static class CamelProcessorInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      // org.apache.camel.Processor 인터페이스를 구현하는 클래스 대상
      return ElementMatchers.hasSuperType(named("org.apache.camel.Processor"));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdviceToMethod(
          named("process")
              .and(takesArguments(1))
              .and(takesArgument(0, named("org.apache.camel.Exchange"))),
          CamelHeaderInstrumentationModule.class.getName() + "$ProcessAdvice"
      );
    }
  }

  /**
   * ProcessAdvice는 process(Exchange) 메서드 호출 시점에 Camel In 메시지의 헤더를 추출하여,
   * 키가 "Camel"으로 시작하는 항목들을 현재 활성 스팬에 "camel.header.<header>" 형태로 추가합니다.
   */
  public static class ProcessAdvice {
  
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Exchange exchange) {


      String contextName = exchange.getContext().getName();
      // Camel Exchange의 In 메시지에서 헤더 추출
      Map<String, Object> headers = exchange.getIn().getHeaders();
      if (headers == null || headers.isEmpty()) {
        return;
      }
      // 전체 헤더 정보를 문자열로 변환하여 "camel.headers"에 저장
      String camelHeaders = headers.toString();
      // 현재 활성 스팬을 가져옴
      Span currentSpan = Span.current();
      if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
        // 전체 헤더 문자열을 우선적으로 저장
        // currentSpan.setAttribute("camel.headers", camelHeaders);
        System.out.print("Added Camel headers as span attribute: " + camelHeaders);
        currentSpan.setAttribute("camel.context.name", contextName);

        // 이후, 전체 헤더에서 개별 항목들을 파싱하여 "camel.headers.<key>" 형태로 추가
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
          String headerKey = entry.getKey();
          Object headerValue = entry.getValue();
          String attributeKey = "camel.headers." + headerKey;
          String attributeValue = headerValue != null ? headerValue.toString() : "null";
          currentSpan.setAttribute(attributeKey, attributeValue);
          System.out.print("Added Camel header attribute: " + attributeKey + " = " + attributeValue);
        }
      }
    }
  }
}
