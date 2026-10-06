package org.example.otel.mdc;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;

import java.util.Collections;
import java.util.List;

public class MDCExtensionModule extends InstrumentationModule {
    public MDCExtensionModule() {
        super("mdc-extension");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new MDCInstrumentation());
    }
}