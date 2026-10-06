package org.example.otel.mdc;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

public class MDCAttacher implements AttributesExtractor<Object, Object> { // 변경

    private static final Logger logger = Logger.getLogger(MDCAttacher.class.getName());

    @Override
    public void onStart(AttributesBuilder attributes, Context context, Object request) {
        logger.info("MDCAttacher.onStart");
        String transactionId = "null";
        try{
            transactionId = context.get(ContextKey.named("transactionId"));
        } catch (Exception e) {
            logger.warning("transactionId is null");
            e.printStackTrace();
        }


        if (transactionId != null) {
            attributes.put("log.mdc.transactionId", transactionId);
        }
    }

    @Override
    public void onEnd(AttributesBuilder attributes, Context context, Object request,
                      @Nullable Object response, @Nullable Throwable error) { // 변경
        // 필요하면 추가적인 로깅 처리 가능
    }
}