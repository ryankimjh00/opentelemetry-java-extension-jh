/*
 * Gradle Kotlin DSL (build.gradle.kts)
 * 목적: Java 8 환경에서 OpenTelemetry Java Agent Extension을 빌드/실행 가능하도록 구성
 * 핵심 포인트
 *  - toolchain/sources/target 모두 Java 8 고정
 *  - 사용 라이브러리 버전은 Java 8 호환 라인으로 유지 (Camel 3.14.x, ByteBuddy 1.14.x 등)
 *  - shadowJar로 all-in-one JAR 생성 + SPI(service files) 안전 병합
 */

plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.example"
version = "1.0.0-SNAPSHOT"

/* ------------------------------------------------------------------
 * Java 8로 고정
 * - toolchain으로 JDK 8 사용 선언
 * - source/targetCompatibility도 1.8로 명시 (일부 빌드 환경에서 중요)
 * ------------------------------------------------------------------ */
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
    // withSourcesJar() // 필요 시 주석 해제: 소스 JAR 생성
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    // Java 8에서 --release 옵션은 사용하지 않음 (사용 시 classfile 버전 충돌 가능)
    // options.release.set(8)  // ← 사용 금지
}

repositories {
    mavenCentral()
}

/* ------------------------------------------------------------------
 * 의존성
 * - OpenTelemetry: 1.32.0 계열은 Java 8 호환
 * - javaagent "bootstrap"은 런타임 전용(runtimeOnly)로 유지
 * - Camel 3.14.x LTS: Java 8 최후 LTS 라인
 * - ByteBuddy 1.14.x: Java 8 호환
 * - auto-service: annotationProcessor + compileOnly 조합
 * ------------------------------------------------------------------ */
dependencies {
    // Prometheus 클라이언트 라이브러리
    implementation("io.prometheus:simpleclient:0.16.0")
    implementation("io.prometheus:simpleclient_httpserver:0.16.0")
    // OpenTelemetry Core & Instrumentation API
    implementation("io.opentelemetry:opentelemetry-api:1.32.0")
    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api:1.32.0")

    // Java Agent Extension API (alpha 계열, Java 8 호환)
    implementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:1.32.0-alpha")

    // Agent Bootstrap: javaagent 구동 시 런타임에만 필요
    runtimeOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-bootstrap:1.33.6-alpha")

    // Apache Camel (Java 8 호환 LTS 라인)
    implementation("org.apache.camel:camel-core:3.14.8")
    implementation("org.apache.camel:camel-api:3.14.8")
    implementation("org.apache.camel:camel-opentelemetry:3.14.8")

    // ByteBuddy (Instrumentation 필수)
    implementation("net.bytebuddy:byte-buddy:1.14.6")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.6")

    // SLF4J API
    implementation("org.slf4j:slf4j-api:1.7.30")

    // Annotations
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("org.jetbrains:annotations:24.0.0")

    // AutoService (컴파일 시 프로세서 + 런타임에는 미포함)
    compileOnly("com.google.auto.service:auto-service:1.0.1")
    annotationProcessor("com.google.auto.service:auto-service:1.0.1")

    // HttpCore5 (Java 8+)
    implementation("org.apache.httpcomponents.core5:httpcore5:5.2.4")

    // (테스트를 JUnit5로 쓸 계획이면 의존성 추가)
    // testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    implementation ("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

tasks.test {
    // JUnit5 사용 시 활성화 (테스트 의존성 추가 필요)
    useJUnitPlatform()
}

/* ------------------------------------------------------------------
 * JAR 매니페스트
 * - javaagent 확장 모듈의 Premain/Agent-Class 지정
 * - Boot-Class-Path는 가급적 고정 경로를 피하고 필요 시만 사용
 *   (환경 고정 경로는 이식성 저하 및 Java 8에서 예상치 못한 로딩 문제 유발 가능)
 * ------------------------------------------------------------------ */
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "org.example.otel.camel.CamelHeaderInstrumentationModule",
            "Agent-Class" to "org.example.otel.camel.CamelHeaderInstrumentationModule",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
            // "Boot-Class-Path" to "/home/observability/Agent/otel-extension-6.0-SNAPSHOT.jar"
            // ↑ 절대 경로는 지양. 실제 필요 시 런타임 스크립트에서 -Xbootclasspath/a 로 주입 권장.
        )
    }
}

/* ------------------------------------------------------------------
 * shadowJar
 * - all-in-one(-all.jar) 생성
 * - SPI(Service Provider) 파일 병합: AutoService 등
 *   ※ OTel Java Agent Extension을 쓴다면, 아래와 같은 SPI 경로도
 *     함께 병합하는 것이 안전 (필요 시 주석 해제)
 *     - META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule
 *     - META-INF/services/io.opentelemetry.javaagent.extension.ignore.IgnoredTypesConfigurer
 * ------------------------------------------------------------------ */
tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles {
        // AutoService 등 표준 SPI 병합
        // include("META-INF/services/*")
        // 필요 시 특정 SPI만 선택적으로 포함 가능:
        include("META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider")
        include("META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule")
    }
}
