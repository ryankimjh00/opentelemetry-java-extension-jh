package org.example.otel.mdc;

import io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge;
import net.bytebuddy.asm.Advice;

public class MDCAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) String key, @Advice.Argument(1) String val) {
        Java8BytecodeBridge.currentSpan().setAttribute("my.otel.attrib." + key, val);
    }
}
