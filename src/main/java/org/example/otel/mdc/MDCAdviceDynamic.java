package org.example.otel.mdc;

import net.bytebuddy.asm.Advice;

import java.util.logging.Logger;

public class MDCAdviceDynamic {
    private static final Logger logger = Logger.getLogger(MDCAdviceDynamic.class.getName());

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
        try {
            logger.info("🔥 MDCAdviceDynamic: preHandle() 호출됨 - 새로운 MDCLoggingAdvice 인스턴스 생성");

            // Java Agent의 ClassLoader를 사용하여 `MDCLoggingAdvice` 클래스 로드
            ClassLoader agentClassLoader = MDCAdviceDynamic.class.getClassLoader();
            Class<?> adviceClass = Class.forName("org.example.otel.mdc.MDCLoggingAdvice", true, agentClassLoader);

            Object adviceInstance = adviceClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method onEnterMethod = adviceClass.getMethod("onEnter");
            onEnterMethod.invoke(adviceInstance);

            logger.info("MDCAdviceDynamic: MDCLoggingAdvice.onEnter() 실행 완료");

        } catch (Exception e) {
            logger.severe("MDCAdviceDynamic: MDCLoggingAdvice 실행 실패! " + e.getMessage());
            e.printStackTrace();
        }
    }
}
