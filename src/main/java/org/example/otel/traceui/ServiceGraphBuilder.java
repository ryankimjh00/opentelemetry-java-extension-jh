package org.example.otel.traceui;

import java.util.*;

final class ServiceGraphBuilder {
    /** 스팬 목록으로부터 서비스 디펜던시 그래프 생성 */
    static ServiceGraph buildSpanGraph(List<SpanView> spans) {
        if (spans == null || spans.isEmpty()) {
            return new ServiceGraph(
                    java.util.Collections.<ServiceGraph.Node>emptyList(),
                    java.util.Collections.<ServiceGraph.Edge>emptyList());
        }

        // spanId -> SpanView 빠른 조회용
        java.util.Map<String, SpanView> byId = new java.util.HashMap<String, SpanView>(spans.size() * 2);
        for (SpanView s : spans) {
            if (s != null && s.spanId != null)
                byId.put(s.spanId, s);
        }

        // 1) 노드: 스팬 개수만큼
        java.util.List<ServiceGraph.Node> nodes = new java.util.ArrayList<ServiceGraph.Node>(spans.size());
        for (SpanView s : spans) {
            String nm = (s.name == null) ? "" : s.name;
            String label = nm;
            nodes.add(new ServiceGraph.Node(s.spanId, label, 1));
        }

        // 2) 엣지: parent -> child (동일 서비스/타 서비스 구분 없이 전부 연결)
        java.util.List<ServiceGraph.Edge> edges = new java.util.ArrayList<ServiceGraph.Edge>();
        for (SpanView child : spans) {
            if (child.parentSpanId == null || child.parentSpanId.isEmpty())
                continue; // 루트면 스킵
            if (!byId.containsKey(child.parentSpanId))
                continue; // 부모가 없으면 스킵
            double durMs = Math.max(0, child.endNanos - child.startNanos) / 1_000_000.0;
            String id = child.parentSpanId + "->" + child.spanId;
            edges.add(new ServiceGraph.Edge(id, child.parentSpanId, child.spanId, 1, durMs));
        }

        return new ServiceGraph(nodes, edges);
    }

    ServiceGraph build(List<SpanView> spans) {
        if (spans == null || spans.isEmpty()) {
            return new ServiceGraph(
                    Collections.<ServiceGraph.Node>emptyList(),
                    Collections.<ServiceGraph.Edge>emptyList());
        }

        Map<String, SpanView> byId = new HashMap<String, SpanView>(spans.size() * 2);
        for (SpanView s : spans) {
            if (s != null && s.spanId != null) {
                byId.put(s.spanId, s);
            }
        }

        Map<String, Integer> nodeCount = new HashMap<String, Integer>();
        class Acc {
            int calls;
            double sumDur;
        }
        Map<String, Acc> edgeAcc = new HashMap<String, Acc>();

        for (SpanView child : spans) {
            String cSvc = serviceName(child);
            Integer cnt = nodeCount.get(cSvc);
            nodeCount.put(cSvc, (cnt == null ? 1 : cnt + 1));

            if (child.parentSpanId == null)
                continue;
            SpanView parent = byId.get(child.parentSpanId);
            if (parent == null)
                continue;

            String pSvc = serviceName(parent);
            // if (pSvc.equals(cSvc))
            // continue;

            double durMs = Math.max(0, child.endNanos - child.startNanos) / 1_000_000.0;

            String key = pSvc + "->" + cSvc;
            Acc acc = edgeAcc.get(key);
            if (acc == null) {
                acc = new Acc();
                edgeAcc.put(key, acc);
            }
            acc.calls += 1;
            acc.sumDur += durMs;
        }

        List<ServiceGraph.Node> nodes = new ArrayList<ServiceGraph.Node>();
        for (Map.Entry<String, Integer> e : nodeCount.entrySet()) {
            nodes.add(new ServiceGraph.Node(e.getKey(), e.getKey(), e.getValue()));
        }
        Collections.sort(nodes, new Comparator<ServiceGraph.Node>() {
            public int compare(ServiceGraph.Node a, ServiceGraph.Node b) {
                return a.id.compareTo(b.id);
            }
        });

        List<ServiceGraph.Edge> edges = new ArrayList<ServiceGraph.Edge>();
        for (Map.Entry<String, Acc> e : edgeAcc.entrySet()) {
            String[] ab = e.getKey().split("->", 2);
            Acc a = e.getValue();
            double avg = (a.calls == 0) ? 0d : (a.sumDur / a.calls);
            edges.add(new ServiceGraph.Edge(e.getKey(), ab[0], ab[1], a.calls, avg));
        }
        Collections.sort(edges, new Comparator<ServiceGraph.Edge>() {
            public int compare(ServiceGraph.Edge a, ServiceGraph.Edge b) {
                return b.calls - a.calls;
            }
        });

        return new ServiceGraph(nodes, edges);
    }

    private static String serviceName(SpanView s) {
        if (s == null)
            return "unknown";
        String t = (s.service == null) ? "" : s.service.trim();
        return t.isEmpty() ? "unknown" : t;
    }
}
