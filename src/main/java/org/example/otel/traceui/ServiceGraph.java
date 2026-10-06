package org.example.otel.traceui;

import java.util.List;

/**
 * 서비스 디펜던시 그래프 DTO.
 * - nodes: service.name 기준 서비스 노드
 * - edges: parent.service -> child.service 호출 간선
 */
final class ServiceGraph {
    static final class Node {
        final String id;       // = service.name
        final String label;    // 표시 라벨
        final int spanCount;   // 해당 서비스 포함 스팬 수

        Node(String id, String label, int spanCount) {
            this.id = id;
            this.label = label;
            this.spanCount = spanCount;
        }
    }

    static final class Edge {
        final String id;          // "A->B"
        final String source;      // A
        final String target;      // B
        final int calls;          // 호출 수
        final double avgDurationMs; // 평균 지속시간(ms)

        Edge(String id, String source, String target, int calls, double avgDurationMs) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.calls = calls;
            this.avgDurationMs = avgDurationMs;
        }
    }

    final List<Node> nodes;
    final List<Edge> edges;

    ServiceGraph(List<Node> nodes, List<Edge> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }
}
