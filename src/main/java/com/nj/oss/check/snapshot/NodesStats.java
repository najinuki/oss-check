package com.nj.oss.check.snapshot;

import java.util.List;
import java.util.Map;

/**
 * Parsed {@code _nodes/stats} response, reduced to the per-node stats the
 * rules consume (JVM heap and circuit breakers).
 *
 * @param clusterName cluster name from the response envelope
 * @param nodes       node id → per-node stats
 */
public record NodesStats(
        String clusterName,
        Map<String, NodeStats> nodes) {

    public NodesStats {
        nodes = Map.copyOf(nodes);
    }

    public long dataNodeCount() {
        return nodes.values().stream().filter(NodeStats::isDataNode).count();
    }

    /**
     * @param name     node name (human-facing, unlike the node id map key)
     * @param roles    node roles, e.g. {@code ["cluster_manager", "data", "ingest"]}
     * @param jvm      JVM stats
     * @param breakers breaker name (e.g. {@code "parent"}) → breaker stats
     */
    public record NodeStats(
            String name,
            List<String> roles,
            Jvm jvm,
            Map<String, Breaker> breakers) {

        public NodeStats {
            roles = List.copyOf(roles);
            breakers = Map.copyOf(breakers);
        }

        /** True for any data role, including tiered roles such as {@code data_hot}. */
        public boolean isDataNode() {
            return roles.stream().anyMatch(r -> r.equals("data") || r.startsWith("data_"));
        }
    }

    public record Jvm(Mem mem) {

        public record Mem(
                int heapUsedPercent,
                long heapUsedInBytes,
                long heapMaxInBytes) {
        }
    }

    public record Breaker(
            long limitSizeInBytes,
            long estimatedSizeInBytes,
            double overhead,
            long tripped) {
    }
}