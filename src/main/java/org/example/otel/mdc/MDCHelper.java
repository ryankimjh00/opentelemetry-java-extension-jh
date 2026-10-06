package org.example.otel.mdc;

import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Context;
import org.slf4j.MDC;

public class MDCHelper {
    private static final ContextKey<String> TRANSACTION_ID_KEY = ContextKey.named("sampleKey");
    private static final ContextKey<String> USER_ID_KEY = ContextKey.named("userId");

    public static Context attachMDCToContext(Context context) {
        String transactionId ="is_production_opt";
        // String userId = MDC.get("userId");

        if (transactionId != null) {
            context = context.with(TRANSACTION_ID_KEY, transactionId);
        }
        // if (userId != null) {
        //     context = context.with(USER_ID_KEY, userId);
        // }
        return context;
    }

    public static String getTransactionId(Context context) {
        return context.get(TRANSACTION_ID_KEY);
    }

    public static String getUserId(Context context) {
        return context.get(USER_ID_KEY);
    }
}
