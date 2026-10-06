package org.example.otel.mdc;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * OpenTelemetry Java Agent를 이용하여 포트별 네트워크 연결 개수를 측정하는 클래스
 */
public class NetworkConnectionMetricsInstrumentation extends InstrumentationModule {

    private final Meter meter;

    public NetworkConnectionMetricsInstrumentation() {
        super("network-connection-metrics", "network");

        // OpenTelemetry MeterProvider에서 Meter 가져오기
        MeterProvider meterProvider = GlobalOpenTelemetry.getMeterProvider();
        this.meter = meterProvider.get("network-connection-metrics");

        // Metrics 초기화
        initializeMetrics();
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.emptyList(); // 특정 클래스를 감지하지 않음
    }

    /**
     * OpenTelemetry Gauge를 초기화하고 네트워크 연결 상태를 지속적으로 업데이트하는 메서드
     */
    private void initializeMetrics() {
        // Gauge를 등록하여 매번 최신 데이터를 제공
        meter.gaugeBuilder("connection_state_count")
                .setDescription("Number of connections per port and state")
                .setUnit("connections")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Map<String, Map<String, Long>> connectionData = getConnectionCounts();
                    for (Map.Entry<String, Map<String, Long>> entry : connectionData.entrySet()) {
                        String localPort = entry.getKey();
                        for (Map.Entry<String, Long> stateEntry : entry.getValue().entrySet()) {
                            measurement.record(stateEntry.getValue(),
                                    Attributes.builder()
                                            .put("local_port", localPort)
                                            .put("state", stateEntry.getKey())
                                            .build()
                            );
                        }
                    }
                });

        // 백그라운드 스레드에서 주기적으로 데이터를 갱신
        startMonitoring();
    }

    /**
     * 백그라운드 스레드에서 주기적으로 네트워크 상태를 갱신하는 메서드
     */
    private void startMonitoring() {
        new Thread(() -> {
            while (true) {
                try {
                    // 5초마다 실행
                    Thread.sleep(5000);
                    getConnectionCounts(); // 최신 데이터를 가져오도록 실행
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Java Agent: 네트워크 모니터링 스레드 중단됨");
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "NetworkConnectionMonitor").start();
    }

    /**
     * 현재 네트워크 연결 상태를 가져오는 메서드
     *
     * @return 포트별 연결 상태 개수를 담은 Map
     */
    private Map<String, Map<String, Long>> getConnectionCounts() {
        Map<String, Map<String, Long>> connectionCounts = new HashMap<>();
        try {
            Process process = Runtime.getRuntime().exec("netstat -an");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 4) continue;

                String state = tokens[tokens.length - 1]; // 마지막 토큰이 상태 값
                String localAddress = tokens[1]; // 두 번째 토큰이 로컬 주소

                if (!state.matches("ESTABLISHED|TIME_WAIT|LISTEN|CLOSE|CLOSE_WAIT")) continue;

                String[] addressParts = localAddress.split(":");
                if (addressParts.length < 2) continue;

                String localPort = addressParts[addressParts.length - 1]; // 포트 추출

                connectionCounts.putIfAbsent(localPort, new HashMap<>());
                Map<String, Long> stateCounts = connectionCounts.get(localPort);
                stateCounts.put(state, stateCounts.getOrDefault(state, 0L) + 1);
            }

            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return connectionCounts;
    }
}
