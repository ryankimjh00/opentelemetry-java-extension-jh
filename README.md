# OpenTelemetry Java Agent Extensions

이 프로젝트는 OpenTelemetry Java Agent를 확장하여 **Modeler 시스템, Apache Camel, Trace 시작 시간 추적, 메모리 기반 Trace 저장소, 서비스 그래프, 경량 Trace UI** 기능을 제공합니다.  
운영 환경에서 서비스 호출 관계와 트레이스 흐름을 직관적으로 파악하고, 디버깅 및 성능 분석을 지원하는 것을 목표로 합니다.


https://github.com/user-attachments/assets/a273c52d-a80e-436c-9dbd-6ffee7f667f0

---

## 1. 프로젝트 개요

- **대상**: OpenTelemetry Java Agent 환경
- **목적**
  - 특정 패키지(Modeler)의 메서드 실행을 자동 추적
  - Apache Camel 메시지 헤더를 스팬 속성으로 기록
  - 트레이스 시작 시각과 루트 스팬 여부를 속성으로 제공
  - 메모리 기반 저장소를 활용한 경량 UI 및 서비스 종속성 그래프 제공

---

## 2. 주요 구성 요소

### 2.1 Instrumentation
- **ModelerInstrumentationModule**
  - `com.indigo.esb.modeler` 내 모든 메서드 추적
  - 메서드 시작/종료 시 스팬 생성 및 종료
  - 에러 발생 시 예외 기록 및 상태 변경

- **CamelHeaderInstrumentationModule**
  - `Processor#process(Exchange)` 메서드 인터셉트
  - 메시지 헤더(`Camel*`)와 컨텍스트 정보를 스팬 속성으로 저장
  - 메시지 라우팅 흐름을 추적 가능

### 2.2 Span Processor
- **TraceStartTimeSpanProcessor**
  - 트레이스 시작 시간 기록 (`trace.start.time`, `trace.start.time.readable`)
  - 루트 스팬 여부 판별 속성(`is.root.span`, `is.root.viaData`) 부여
- **InMemorySpanProcessor**
  - 메모리 기반 스팬 수집 및 보관
  - Trace UI와 연계하여 조회 가능

### 2.3 Trace 저장소 및 UI
- **TraceStore**
  - TTL + LRU 기반 메모리 저장소
  - 트레이스별 최대 스팬 수 제한
- **TraceUiExtension**
  - 경량 HTTP 서버 내장
  - `/api/trace` 및 HTML UI 제공
- **HtmlTemplateRenderer / TraceDetailRenderer / TinyTemplate**
  - HTML 기반 경량 렌더러
  - 타임라인, 서비스 그래프, 스팬 상세 정보 제공

### 2.4 서비스 그래프
- **ServiceGraph / ServiceGraphBuilder**
  - 스팬 간 호출 관계를 기반으로 서비스 종속성 그래프 생성
  - 서비스 간 의존성 시각화 지원

---

## 3. 빌드 및 설치

### 요구사항
- Java 8 (JDK 1.8 로 구성하여 레거시 시스템에서도 동작 가능하도록 구성함함)
- Gradle (Kotlin DSL)

### 빌드 방법
```bash
git clone <repo-url>
cd <project-root>
./gradlew clean build shadowJar

```

## 4. 실행 방법

### Java Agent 실행 예시
```bash
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=/path/to/extension.jar \
  -Dotel.resource.attributes=service.name=my-service \
  -jar my-application.jar
````

### Trace UI 확인

* **기본 포트**: `TraceUiExtension`에서 설정한 값 (예: `8080`)
* **브라우저 접속**: [http://localhost:8080](http://localhost:8080)

### otel.properties 파일 설정 예시
이 extension 을 적용하면 다음과 같은 otel.properties 설정이 활성화 됩니다.
```properties
# Trace UI 설정
otel.javaagent.extension.traceui.bind=127.0.0.1     # 0.0.0.0(기본)
otel.javaagent.extension.traceui.port=55679         # Trace UI 가 뜰 포트(기본 :55679)
otel.javaagent.extension.traceui.maxTraces=64       # 최대 저장 가능한 Trace 수 (기본 64개)
otel.javaagent.extension.traceui.maxSpansPerTrace=2048 # 한 Trace 당 최대 Span 수 (기본 2048개)
otel.javaagent.extension.traceui.ttlSeconds=600     # Trace 저장 시간(기본 600초)
otel.javaagent.extension.traceui.indexLimit=64      # 한 페이지에 보여줄 최대 Trace 수(기본 64개)
otel.javaagent.extension.traceui.host=172.28.6.31   # 리다이렉트될 로컬 IP 주소(외부 접속 용도)
```

혹은 -D 옵션으로도 가능합니다.

```
java -javaagent:./otel-javaagent.jar \

-Dotel.javaagent.extension.traceui.bind=127.0.0.1     \
-Dotel.javaagent.extension.traceui.port=55679         \
-Dotel.javaagent.extension.traceui.maxTraces=64       \
-Dotel.javaagent.extension.traceui.maxSpansPerTrace=2048  \
-Dotel.javaagent.extension.traceui.ttlSeconds=600     \
-Dotel.javaagent.extension.traceui.indexLimit=64      \
-Dotel.javaagent.extension.traceui.host=172.28.6.31   \

-jar application.jar

```

#### 주요 페이지

* `/trace/{traceId}` : 단일 트레이스 타임라인 및 상세
* `/trace/{traceId}/service-graph` : 서비스 종속성 그래프

---

## 6. 프로젝트 구조

```
src/main/java/org/example/otel/
 ├── modeler/
 │   └── ModelerInstrumentationModule.java
 ├── camel/
 │   └── CamelHeaderInstrumentationModule.java
 ├── trace/
 │   ├── TraceStartTimeProvider.java
 │   └── TraceStartTimeSpanProcessor.java
 ├── traceui/
 │   ├── TraceUiExtension.java
 │   ├── TraceStore.java
 │   ├── ServiceGraph.java
 │   ├── ServiceGraphBuilder.java
 │   ├── SpanView.java
 │   ├── SpanDetail.java
 │   ├── HtmlTemplateRenderer.java
 │   ├── TraceDetailRenderer.java
 │   └── TinyTemplate.java
 └── common/
     └── InMemorySpanProcessor.java
```
