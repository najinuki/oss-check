package com.nj.oss.check.snapshot;

/**
 * Parsed {@code _cluster/health} response (cluster-level fields only).
 */
public record ClusterHealth(
        String clusterName,
        HealthStatus status,
        int numberOfNodes,
        int numberOfDataNodes,
        int activePrimaryShards,
        int activeShards,
        int relocatingShards,
        int initializingShards,
        int unassignedShards,
        int delayedUnassignedShards,
        double activeShardsPercentAsNumber) {
}