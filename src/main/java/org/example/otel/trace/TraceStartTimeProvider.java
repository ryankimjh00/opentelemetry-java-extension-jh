package org.example.otel.trace;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

@AutoService(AutoConfigurationCustomizerProvider.class)
public class TraceStartTimeProvider implements AutoConfigurationCustomizerProvider {
    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration
                .addTracerProviderCustomizer(this::configureTracerProvider);
    }

    private SdkTracerProviderBuilder configureTracerProvider(
            SdkTracerProviderBuilder builder,
            io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties config) {
        return builder.addSpanProcessor(new TraceStartTimeSpanProcessor());
    }
}